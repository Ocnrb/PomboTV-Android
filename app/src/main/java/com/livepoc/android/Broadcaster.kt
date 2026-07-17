package com.livepoc.android

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Native broadcaster: Camera2 (preview + encoder surface) → MediaCodec H.264,
 * AudioRecord → MediaCodec Opus. Output framed with Wire (identical to the web
 * POC: keyframes ride alone possibly fragmented, deltas bundled ×3, opus ×4,
 * 120ms flush). Config JSON on every keyframe, Annex-B with inline SPS/PPS —
 * the web viewer configures WebCodecs in Annex-B mode (no description).
 */
class Broadcaster(
    private val context: Context,
    private val bridge: StreamrBridge,
    previewSurface: Surface?,
    private var width: Int,
    private var height: Int,
    private val bitrate: Int,
    private val kfIntervalMs: Int,
    private val cameraId: String?, // null → first back camera
    private val audioBitrate: Int = 64_000,
    // chamada 1:1: kinds cv/ca (partições definidas pelo bridgeCallStart) e sem
    // gestão de overlays (o callStart aplica os proxies de publish)
    private val kindVideo: String = "video",
    private val kindAudio: String = "audio",
    private val manageOverlays: Boolean = true,
    // EXPERIMENTAL — A/V na MESMA partição (o recetor demultiplexa por FLAG_AUD):
    // singlePartition: áudio publica-se na partição de vídeo (mensagens próprias)
    // muxAV: áudio entra nos MESMOS containers do vídeo (menos msgs/s; o áudio
    //        herda o destino/cadência do vídeo). muxAV implica singlePartition.
    private val singlePartition: Boolean = false,
    private val muxAV: Boolean = false,
    // PARTILHA DE ECRÃ: em vez da câmara, um VirtualDisplay (MediaProjection)
    // espelha o ecrã diretamente para a surface do encoder. Sem preview (o
    // espelho de si próprio seria recursivo), sem torch/zoom, rotation=0.
    // (é o valor INICIAL — a fonte pode mudar em pleno live via setVideoSource)
    screenProjection: android.media.projection.MediaProjection? = null,
    private val onStats: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onCameraInfo: (sensorOrientation: Int, hasTorch: Boolean, minZoom: Float, maxZoom: Float) -> Unit = { _, _, _, _ -> },
    private val onMeter: (txMbps: Double, brMbps: Double) -> Unit = { _, _ -> }
) {
    companion object { private const val TAG = "Broadcaster" }

    @Volatile private var running = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var vCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private val camThread = HandlerThread("camera").apply { start() }
    private val camHandler = Handler(camThread.looper)

    private var csdAnnexB: ByteArray? = null
    private var codecStr = "avc1.42001f"

    // Rebase dos timestamps para ~0 no arranque (por stream): câmara (BOOTTIME)
    // e AudioRecord (nanoTime) têm bases distintas — sem isto o viewer mede
    // offsets A/V enormes e o mapeamento fica frágil (vídeo congelado em gaps).
    @Volatile private var vTsBaseUs = -1.0
    @Volatile private var aTsBaseUs = -1.0

    // stats — same semantics as the POC (bPub counts FRAMES, bMsg counts messages)
    @Volatile private var bEnc = 0
    @Volatile private var bPub = 0
    @Volatile private var bMsg = 0
    @Volatile private var bKfBytes = 0
    @Volatile private var bKfFrags = 0

    // Application-level congestion control (the WebRTC/SCTP layer under the
    // Streamr SDK has its own; what WE control is how much we feed it).
    // Signal: bytes accepted for publish but not yet drained (bridge.inFlight()).
    @Volatile private var curBitrate = bitrate
    @Volatile private var dropUntilKey = false
    private var calmSecs = 0

    // Live controls (UI): muted mic keeps packets flowing — the viewers' audio
    // master clock depends on them; torch; digital zoom.
    @Volatile private var micMuted = false
    @Volatile private var torchOn = false
    @Volatile private var zoomRatio = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var zoomRange: Range<Float>? = null
    private var activeRect: android.graphics.Rect? = null
    private var reqBuilder: CaptureRequest.Builder? = null
    private var charsRef: CameraCharacteristics? = null

    // Orientação: o encoder recebe o buffer NA ORIENTAÇÃO DO SENSOR (paisagem);
    // o ângulo para endireitar segue em streamSettings.rotation nos keyframes —
    // os viewers rodam no render. Atualizado pela Activity quando o ecrã roda.
    @Volatile var displayRotationDeg = 0
    private var sensorOrientation = 90
    private var facingFront = false
    // fonte ATUAL de vídeo (muda em pleno live via setVideoSource)
    @Volatile private var screenProj: android.media.projection.MediaProjection? = screenProjection
    /** A emitir o ecrã (vs câmara)? — decide rotação, áudio e controlos. */
    fun isScreenSource(): Boolean = screenProj != null
    private fun contentRotation(): Int =
        if (screenProj != null) 0 // o espelho do ecrã já vem direito
        else if (facingFront) (sensorOrientation + displayRotationDeg) % 360
        else (sensorOrientation - displayRotationDeg + 360) % 360

    // Detachable preview (background support): with the activity hidden the
    // SurfaceView dies — the capture session is rebuilt with the encoder as the
    // only target and the broadcast keeps running.
    @Volatile private var previewSurf: Surface? = previewSurface

    // câmara ATUAL (pode mudar em pleno live — switchCamera)
    @Volatile private var curCameraId: String? = cameraId

    fun setMicMuted(m: Boolean) { micMuted = m }

    /** Troca de câmara SEM quebrar a transmissão: o encoder (e a sua surface)
     *  ficam vivos — só a câmara/sessão de captura são reconstruídas. O viewer
     *  vê um congelamento de ~0,3-0,5s e a rotação/config atualiza no keyframe
     *  seguinte (contentRotation usa o novo sensor/facing). */
    fun switchCamera(newId: String?) {
        if (newId == null || newId == curCameraId) return
        curCameraId = newId
        camHandler.post {
            try { session?.close() } catch (e: Exception) {}
            session = null
            try { camera?.close() } catch (e: Exception) {}
            camera = null
            torchOn = false; zoomRatio = 1f // estado de lente não sobrevive à troca
            if (running) openCamera()
        }
    }

    fun setTorch(on: Boolean) { torchOn = on; pushRequest() }

    fun setZoom(ratio: Float) { zoomRatio = ratio.coerceIn(minZoom, maxZoom); pushRequest() }

    fun setPreviewSurface(s: Surface?) {
        if (previewSurf === s) return
        previewSurf = s
        val cam = camera ?: return
        val ch = charsRef ?: return
        camHandler.post {
            try { session?.close() } catch (e: Exception) {}
            session = null
            if (running) createSession(cam, ch)
        }
    }

    /** Re-issues the repeating request with the current torch/zoom state. */
    private fun pushRequest() {
        val s = session ?: return
        val b = reqBuilder ?: return
        camHandler.post {
            try {
                applyLiveControls(b)
                s.setRepeatingRequest(b.build(), null, camHandler)
            } catch (e: Exception) {}
        }
    }

    private fun applyLiveControls(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        val zr = zoomRange
        if (android.os.Build.VERSION.SDK_INT >= 30 && zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(zr.lower, zr.upper))
        } else {
            val a = activeRect ?: return
            val r = zoomRatio.coerceAtLeast(1f) // crop region can't go below 1×
            val w = (a.width() / r).toInt()
            val h = (a.height() / r).toInt()
            val l = a.left + (a.width() - w) / 2
            val t = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(l, t, l + w, t + h))
        }
    }

    private val vBatch = ArrayList<ByteArray>()
    private val aBatch = ArrayList<ByteArray>()

    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var statsThread: Thread? = null

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    /** Liga o VirtualDisplay do MediaProjection atual ao encoder. */
    private fun attachScreenSource(): Boolean {
        val proj = screenProj ?: return false
        return try {
            // Android 14+ exige um callback registado ANTES do createVirtualDisplay
            proj.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { if (running && screenProj === proj) onError("Screen sharing stopped.") }
            }, camHandler)
            val dpi = context.resources.displayMetrics.densityDpi
            virtualDisplay = proj.createVirtualDisplay(
                "pombotv-screen", width, height, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface, null, null)
            onCameraInfo(0, false, 1f, 1f) // sem torch/zoom; conteúdo já direito
            true
        } catch (e: Exception) {
            onError("Screen capture failed: ${e.message}")
            false
        }
    }

    /** Reconstrói o encoder de vídeo com novas dimensões (thread do camHandler).
     *  Os viewers seguem: coded size novo no keyframe → rebuild do decoder. */
    private fun restartVideoEncoder(newW: Int, newH: Int) {
        val oldCodec = vCodec
        vCodec = null // o drain loop rebenta no dequeue e termina
        try { oldCodec?.stop() } catch (e: Exception) {}
        vThread?.join(1500)
        try { oldCodec?.release() } catch (e: Exception) {}
        try { encoderSurface?.release() } catch (e: Exception) {}
        encoderSurface = null
        width = newW; height = newH
        csdAnnexB = null // SPS/PPS novos vêm do encoder novo
        dropUntilKey = false
        startVideoEncoder()
    }

    /** Rotação do ecrã durante a partilha: o VirtualDisplay tem tamanho fixo e
     *  o Android encolhia o conteúdo rodado dentro da moldura antiga — aqui o
     *  encoder renasce transposto e o espelho segue a orientação real. */
    fun resizeScreenCapture(newW: Int, newH: Int) {
        if (screenProj == null || (newW == width && newH == height)) return
        camHandler.post {
            val vd = virtualDisplay ?: return@post
            try {
                vd.surface = null
                restartVideoEncoder(newW, newH)
                vd.resize(newW, newH, context.resources.displayMetrics.densityDpi)
                vd.surface = encoderSurface
                onCameraInfo(0, false, 1f, 1f)
            } catch (e: Exception) { onError("Screen resize failed: ${e.message}") }
        }
    }

    /** TROCA DE FONTE em pleno live (câmara↔câmara↔ecrã) sem parar a emissão:
     *  só a origem e (se preciso) o encoder são reconstruídos — a rede nunca vê
     *  interrupção além de ~0,5s de frames parados. Projection novo por troca
     *  (o consentimento é de uso único no Android 14+). */
    fun setVideoSource(newCamId: String?, newProj: android.media.projection.MediaProjection?, newW: Int, newH: Int) {
        camHandler.post {
            // 1) desligar a fonte atual
            try { virtualDisplay?.surface = null } catch (e: Exception) {}
            try { virtualDisplay?.release() } catch (e: Exception) {}
            virtualDisplay = null
            val oldProj = screenProj
            if (oldProj != null && oldProj !== newProj) { try { oldProj.stop() } catch (e: Exception) {} }
            try { session?.close() } catch (e: Exception) {}
            session = null
            try { camera?.close() } catch (e: Exception) {}
            camera = null
            torchOn = false; zoomRatio = 1f
            screenProj = newProj
            curCameraId = newCamId
            // 2) encoder novo se as dimensões mudarem
            try {
                if (newW != width || newH != height) restartVideoEncoder(newW, newH)
            } catch (e: Exception) { onError("Encoder restart failed: ${e.message}"); return@post }
            // 3) ligar a fonte nova
            if (newProj != null) attachScreenSource() else if (running) openCamera()
        }
    }

    fun start() {
        running = true
        try {
            startVideoEncoder()
        } catch (e: Exception) {
            onError("H.264 encoder failed: ${e.message}")
            return
        }
        if (screenProj != null) {
            if (!attachScreenSource()) return
        } else {
            openCamera()
        }
        aThread = thread(name = "audio-enc") { audioLoop() }
        // Join own overlays as a participant — pure publishers get poor delivery.
        if (manageOverlays) { bridge.join("video"); bridge.join("audio") }
        statsThread = thread(name = "stats") {
            var lastEnc = 0; var lastPub = 0; var lastMsg = 0; var lastAcked = 0L
            while (running) {
                SystemClock.sleep(1000)
                if (!running) break
                congestionControl()
                val enc = bEnc - lastEnc; val pub = bPub - lastPub; val msg = bMsg - lastMsg
                lastEnc = bEnc; lastPub = bPub; lastMsg = bMsg
                val acked = bridge.ackedTotal()
                val txMbps = (acked - lastAcked) * 8 / 1e6
                lastAcked = acked
                val kf = if (bKfBytes > 0) " · kf=${bKfBytes / 1024}KB×$bKfFrags" else ""
                val br = "%.1f".format(curBitrate / 1e6)
                val fly = bridge.inFlight() / 1024
                onMeter(txMbps, curBitrate / 1e6)
                // espelho no logcat: permite medição automatizada (adb) sem UI
                Log.d(TAG, "stats enc=${enc} pub=${pub} br=$br tx=${"%.2f".format(txMbps)} queue=${fly}KB backlog=${bEnc - bPub}")
                onStats("encode=${enc}fps · publish=${pub}fps · msgs=$msg/s · br=${br}Mbps · tx=${"%.2f".format(txMbps)}Mbps · queue=${fly}KB · backlog=${bEnc - bPub}$kf")
            }
        }
    }

    /**
     * Adaptive bitrate, BBR-inspired: probe up while the send queue drains,
     * back off multiplicatively when it doesn't. Note the Streamr overlay
     * fan-out: the broadcaster uploads the stream to ~4 neighbours, so the real
     * uplink cost is ~4× the encoder bitrate — this controller finds what the
     * link actually sustains.
     */
    private fun congestionControl() {
        val inflight = bridge.inFlight()
        val backlog = bEnc - bPub
        val congested = inflight > 256 * 1024 || backlog > 40
        val severe = inflight > 512 * 1024 || backlog > 90
        if (congested) {
            calmSecs = 0
            val next = Math.max(250_000, (curBitrate * 0.7).toInt())
            if (next < curBitrate) {
                curBitrate = next
                try {
                    vCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, curBitrate) })
                } catch (e: Exception) {}
            }
            if (severe && !dropUntilKey) {
                // Everything queued is stale — drop the pending deltas and jump
                // to a fresh keyframe (the viewer re-syncs on it; no corruption,
                // deltas only depend on frames after their keyframe).
                synchronized(vBatch) { bPub += vBatch.size; vBatch.clear() }
                dropUntilKey = true
                try {
                    vCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                } catch (e: Exception) {}
            }
        } else if (++calmSecs >= 10 && curBitrate < bitrate) {
            calmSecs = 0
            curBitrate = Math.min(bitrate, (curBitrate * 1.15).toInt() + 50_000)
            try {
                vCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, curBitrate) })
            } catch (e: Exception) {}
        }
    }

    private fun startVideoEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            // Backup GOP; the real cadence comes from REQUEST_SYNC_FRAME below.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.max(1, kfIntervalMs / 1000))
            try { setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) } catch (e: Exception) {}
            try { setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (e: Exception) {} // 0 = realtime
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        codec.start()
        vCodec = codec
        vThread = thread(name = "video-enc") { videoDrainLoop(codec) }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = curCameraId ?: mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull()
        if (id == null) { onError("No camera available."); return }
        val chars = mgr.getCameraCharacteristics(id)
        charsRef = chars
        zoomRange = if (android.os.Build.VERSION.SDK_INT >= 30)
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        minZoom = zoomRange?.lower ?: 1f // <1 = ultra-wide via logical camera
        maxZoom = zoomRange?.upper
            ?: (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        facingFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        onCameraInfo(sensorOrientation,
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true, minZoom, maxZoom)
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    if (!running) { cam.close(); return }
                    createSession(cam, chars)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close(); onError("Camera error: $error")
                }
            }, camHandler)
        } catch (e: Exception) { onError("No camera access: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice, chars: CameraCharacteristics) {
        val enc = encoderSurface ?: return
        val targets = listOfNotNull(previewSurf, enc)
        // Negotiate the FPS range: forcing (30,30) throws on cameras without it.
        // Prefer upper close to 30 and a narrow range (steady timestamps).
        val fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.minByOrNull { r -> Math.abs(r.upper - 30) * 100 + (r.upper - r.lower) }
            ?: Range(30, 30)
        try {
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    if (!running) return
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        previewSurf?.let { addTarget(it) }
                        addTarget(enc)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    }
                    reqBuilder = req
                    applyLiveControls(req)
                    try { s.setRepeatingRequest(req.build(), null, camHandler) }
                    catch (e: Exception) { onError("Capture failed: ${e.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { onError("Camera session failed.") }
            }, camHandler)
        } catch (e: Exception) { onError("Camera session: ${e.message}") }
    }

    // ---------- video ----------
    private fun videoDrainLoop(codec: MediaCodec) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        var lastKfReq = SystemClock.elapsedRealtime()
        var lastFlush = SystemClock.elapsedRealtime()
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastKfReq >= kfIntervalMs) {
                lastKfReq = now
                try {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                } catch (e: Exception) {}
            }
            val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (idx >= 0) {
                val bb = codec.getOutputBuffer(idx)
                if (bb != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    bb.position(info.offset); bb.get(data)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        csdAnnexB = data
                        val (sps, _) = AvcUtils.extractSpsPps(data)
                        if (sps != null) codecStr = AvcUtils.codecString(sps)
                    } else {
                        onVideoChunk(data, info)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            }
            if (SystemClock.elapsedRealtime() - lastFlush >= Wire.BATCH_MS) {
                lastFlush = SystemClock.elapsedRealtime()
                flushVBatch()
            }
        }
    }

    private fun onVideoChunk(raw: ByteArray, info: MediaCodec.BufferInfo) {
        bEnc++
        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        // Severe congestion: skip stale deltas until the requested keyframe lands.
        if (dropUntilKey) {
            if (isKey) dropUntilKey = false
            else { bPub++; return }
        }
        // Hard gate: with the send queue saturated, feeding more deltas only
        // grows latency. Degrade to keyframes+audio (slideshow with voice).
        // Dropping one delta breaks the reference chain — stay in drop-until-key
        // so no orphan deltas reach the viewers (artifacts).
        if (!isKey && bridge.inFlight() > 700 * 1024) { dropUntilKey = true; bPub++; return }
        // Every keyframe must carry SPS/PPS inline (Annex-B mode on the web viewer,
        // late-join on both). Skip the prepend if the encoder already did it.
        var data = raw
        if (isKey) {
            val csd = csdAnnexB
            if (csd != null && AvcUtils.firstNalType(raw) != 7) data = csd + raw
        }
        val rawTs = info.presentationTimeUs.toDouble()
        if (vTsBaseUs < 0) vTsBaseUs = rawTs
        val ts = rawTs - vTsBaseUs
        val configJson = if (isKey) buildConfigJson() else null
        val records = Wire.packMedia(isKey, ts, 0.0, data, configJson)
        if (isKey) {
            bKfBytes = data.size; bKfFrags = records.size
            flushVBatch() // keep order: pending deltas first
            for (r in records) { bridge.publish(kindVideo, Wire.container(listOf(r))); bMsg++ }
            bPub++
        } else {
            synchronized(vBatch) {
                vBatch.add(records[0])
                if (vBatch.size >= Wire.VBATCH_MAX) flushVBatchLocked()
            }
        }
    }

    // Config the web viewer feeds straight into VideoDecoder.configure(): no
    // description → WebCodecs treats the bitstream as Annex-B.
    private fun buildConfigJson(): String = JSONObject().apply {
        put("codec", codecStr)
        put("codedWidth", width)
        put("codedHeight", height)
        // relógio de parede do keyframe: os viewers medem a latência E2E real
        // (render − captura) assumindo relógios ~sincronizados (NTP)
        put("wallMs", System.currentTimeMillis())
        put("streamSettings", JSONObject().apply {
            put("width", width); put("height", height)
            put("framerate", 30); put("bitrate", bitrate)
            put("rotation", contentRotation())
        })
    }.toString()

    private fun flushVBatch() = synchronized(vBatch) { flushVBatchLocked() }
    private fun flushVBatchLocked() {
        if (vBatch.isEmpty()) return
        val recs = ArrayList(vBatch); vBatch.clear()
        bridge.publish(kindVideo, Wire.container(recs))
        bPub += recs.size; bMsg++
    }

    // ---------- audio ----------
    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sampleRate = 48000
        val chunkBytes = 1920 // 20ms mono 16-bit @48k = 960 samples
        var codec: MediaCodec? = null
        var rec: AudioRecord? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            onError("Opus encoder unavailable (${e.message}) — broadcasting video only.")
            return
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // VOICE_COMMUNICATION ≈ echoCancellation+noiseSuppression of the web POC
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, chunkBytes * 4))
            rec.startRecording()
        } catch (e: Exception) {
            onError("Microphone unavailable: ${e.message}")
            codec.release(); return
        }

        // Partilha de ecrã: captura também o ÁUDIO DO DISPOSITIVO (AudioPlaybackCapture
        // do mesmo MediaProjection) e mistura-o com o micro. Apps que bloqueiam
        // captura (política de mídia) simplesmente não aparecem.
        var devRec: AudioRecord? = null
        val proj = screenProj
        if (proj != null && android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val cfg = android.media.AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                    .build()
                devRec = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuf * 2, chunkBytes * 4))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                devRec.startRecording()
                Log.i(TAG, "device audio capture ativo (screen share)")
            } catch (e: Exception) {
                devRec = null
                Log.w(TAG, "playback capture indisponível: ${e.message}")
            }
        }

        val pcm = ByteArray(chunkBytes)
        val devPcm = ByteArray(chunkBytes)
        val info = MediaCodec.BufferInfo()
        var lastFlush = SystemClock.elapsedRealtime()
        while (running) {
            try {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    var off = 0
                    while (off < chunkBytes && running) {
                        val n = rec.read(pcm, off, chunkBytes - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (micMuted) java.util.Arrays.fill(pcm, 0, off, 0)
                    // mistura do áudio do dispositivo (LE 16-bit, soma com clip)
                    if (devRec != null && off > 0) {
                        val n2 = devRec.read(devPcm, 0, off, AudioRecord.READ_NON_BLOCKING)
                        var i = 0
                        while (i + 1 < n2) {
                            val a = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                            val b = (devPcm[i].toInt() and 0xFF) or (devPcm[i + 1].toInt() shl 8)
                            val m = (a + b).coerceIn(-32768, 32767)
                            pcm[i] = (m and 0xFF).toByte()
                            pcm[i + 1] = (m shr 8).toByte()
                            i += 2
                        }
                    }
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear(); ib.put(pcm, 0, off)
                    codec.queueInputBuffer(inIdx, 0, off, System.nanoTime() / 1000, 0)
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    val bb = codec.getOutputBuffer(out)
                    if (bb != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val d = ByteArray(info.size)
                        bb.position(info.offset); bb.get(d)
                        onAudioPacket(d, info.presentationTimeUs.toDouble())
                    }
                    codec.releaseOutputBuffer(out, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio loop", e); break
            }
            if (SystemClock.elapsedRealtime() - lastFlush >= Wire.BATCH_MS) {
                lastFlush = SystemClock.elapsedRealtime()
                flushABatch()
            }
        }
        try { rec.stop() } catch (e: Exception) {}
        rec.release()
        try { devRec?.stop() } catch (e: Exception) {}
        try { devRec?.release() } catch (e: Exception) {}
        try { codec.stop() } catch (e: Exception) {}
        codec.release()
    }

    private fun onAudioPacket(data: ByteArray, tsUs: Double) {
        if (aTsBaseUs < 0) aTsBaseUs = tsUs
        val rec = Wire.packMedia(true, tsUs - aTsBaseUs, 20_000.0, data, null, isAudio = true)[0]
        if (muxAV) {
            // mux: o áudio viaja nos containers do vídeo (flush pela cadência dele)
            synchronized(vBatch) {
                vBatch.add(rec)
                if (vBatch.size >= Wire.VBATCH_MAX + Wire.ABATCH_MAX) flushVBatchLocked()
            }
            return
        }
        synchronized(aBatch) {
            aBatch.add(rec)
            if (aBatch.size >= Wire.ABATCH_MAX) flushABatchLocked()
        }
    }
    private fun flushABatch() = synchronized(aBatch) { flushABatchLocked() }
    private fun flushABatchLocked() {
        if (aBatch.isEmpty()) return
        val recs = ArrayList(aBatch); aBatch.clear()
        // single-partition: mensagens de áudio próprias, mas na partição de vídeo
        bridge.publish(if (singlePartition) kindVideo else kindAudio, Wire.container(recs))
    }

    fun stop() {
        running = false
        try { virtualDisplay?.release() } catch (e: Exception) {}
        virtualDisplay = null
        try { screenProj?.stop() } catch (e: Exception) {}
        try { session?.stopRepeating() } catch (e: Exception) {}
        try { session?.close() } catch (e: Exception) {}
        try { camera?.close() } catch (e: Exception) {}
        session = null; camera = null
        vThread?.join(1500); aThread?.join(1500); statsThread?.join(1500)
        try { vCodec?.stop() } catch (e: Exception) {}
        try { vCodec?.release() } catch (e: Exception) {}
        vCodec = null
        try { encoderSurface?.release() } catch (e: Exception) {}
        encoderSurface = null
        camThread.quitSafely()
        if (manageOverlays) { bridge.leave("video"); bridge.leave("audio") }
    }
}
