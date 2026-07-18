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
    // SINGLE-PARTITION (modo global): áudio publica-se na partição de VÍDEO com
    // FLAG_AUD (mensagens próprias); o recetor demultiplexa e a #1 é ignorada.
    private val singlePartition: Boolean = false,
    // PARTILHA DE ECRÃ: em vez da câmara, um VirtualDisplay (MediaProjection)
    // espelha o ecrã diretamente para a surface do encoder. Sem preview (o
    // espelho de si próprio seria recursivo), sem torch/zoom, rotation=0.
    // (é o valor INICIAL — a fonte pode mudar em pleno live via setVideoSource)
    screenProjection: android.media.projection.MediaProjection? = null,
    // MEETING/espectador: arrancar SEM fonte de vídeo (câmara fechada, LED off);
    // o encoder fica idle e só o áudio publica. Ligar depois = setVideoSource.
    private val startWithVideo: Boolean = true,
    private val onStats: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onCameraInfo: (sensorOrientation: Int, hasTorch: Boolean, minZoom: Float, maxZoom: Float) -> Unit = { _, _, _, _ -> },
    private val onMeter: (txMbps: Double, brMbps: Double, fps: Int) -> Unit = { _, _, _ -> }
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
    // teto de bitrate — o máximo que o congestion-control persegue. Ajustável
    // pelo bitrate adaptativo do meeting (baixa conforme entram participantes,
    // sobe quando saem), independente do controlo reativo por backpressure.
    @Volatile private var bitrateCeiling = bitrate
    @Volatile private var dropUntilKey = false
    private var calmSecs = 0

    // bitrate BASE da sessão: o teto que o utilizador escolheu. Começa no valor
    // do construtor mas o menu de definições em sessão pode subi-lo/baixá-lo.
    @Volatile private var baseBitrate = bitrate

    /** Teto de bitrate adaptativo (meeting): baixa já o curBitrate se preciso;
     *  o congestion-control passa a subir só até este teto. */
    fun setBitrateCeiling(bps: Int) {
        bitrateCeiling = bps.coerceIn(150_000, baseBitrate)
        if (curBitrate > bitrateCeiling) {
            curBitrate = bitrateCeiling
            try { vCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, curBitrate) }) } catch (e: Exception) {}
        }
    }

    /** Bitrate de vídeo escolhido em pleno live (menu de definições). Aplica-se
     *  já ao encoder — sem reinício, sem corte na emissão. */
    fun changeBitrate(bps: Int) {
        val v = bps.coerceIn(150_000, 20_000_000)
        baseBitrate = v; bitrateCeiling = v; curBitrate = v
        try { vCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, v) }) } catch (e: Exception) {}
    }

    /** Bitrate de ÁUDIO em pleno live. O Opus não muda de bitrate a meio, por
     *  isso a thread de áudio renasce (curto silêncio; o vídeo não é tocado). */
    @Volatile private var curAudioBitrate = audioBitrate
    @Volatile private var aRestart = false
    fun changeAudioBitrate(bps: Int) {
        val v = bps.coerceIn(8_000, 320_000)
        if (v == curAudioBitrate) return
        curAudioBitrate = v
        val old = aThread
        aRestart = true // faz o loop atual sair sem parar o broadcast
        thread(name = "audio-restart") {
            try { old?.join(1500) } catch (e: Exception) {}
            aRestart = false
            if (running) aThread = thread(name = "audio-enc") { audioLoop() }
        }
    }

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

    // High-speed (60fps): câmaras que anunciam a resolução mas falham a sessão
    // ou não entregam frames (ex.: frontal). Uma vez marcadas, nunca mais
    // tentamos high-speed nelas — vai direto à sessão normal (30fps).
    private val hsFailedCams = java.util.Collections.synchronizedSet(HashSet<String>())
    // A tentar high-speed AGORA nesta câmara: um erro do dispositivo enquanto
    // isto está ligado é (quase de certeza) da config high-speed → reabrir sem ela.
    @Volatile private var hsAttempting = false
    // Reabrir a câmara SÓ quando o HAL confirmar o close (onClosed). O close() é
    // assíncrono; abrir a nova câmara antes de a antiga libertar dá ERROR_MAX_
    // CAMERAS_IN_USE (2) no Samsung — pior depois de uma sessão high-speed.
    @Volatile private var pendingReopen = false
    // Quebra-cascata: uma tentativa high-speed falhada (frontal) pode deixar o HAL
    // da câmara instável e a propagar erros 3/4 até à câmara traseira que FUNCIONA.
    // Ao primeiro erro/fallback, desligamos high-speed em TODA a sessão → tudo a
    // 30fps estável e o HAL recupera. (60fps volta ao reiniciar o broadcast.)
    @Volatile private var hsGloballyDisabled = false
    private var camFatalRetries = 0
    // Lanterna a 60fps: a sessão high-speed ignora FLASH_MODE e o setTorchMode dá
    // CAMERA_IN_USE na própria câmara. Enquanto a lanterna estiver ligada, força
    // sessão normal (30fps) nessa câmara para o FLASH_MODE_TORCH funcionar.
    @Volatile private var torchForcesNormal = false

    fun setMicMuted(m: Boolean) { micMuted = m }

    /** Troca de câmara SEM quebrar a transmissão: o encoder (e a sua surface)
     *  ficam vivos — só a câmara/sessão de captura são reconstruídas. O viewer
     *  vê um congelamento de ~0,3-0,5s e a rotação/config atualiza no keyframe
     *  seguinte (contentRotation usa o novo sensor/facing). */
    fun switchCamera(newId: String?) {
        if (newId == null || newId == curCameraId) return
        curCameraId = newId
        camHandler.post {
            clearIndependentTorch()
            torchOn = false; zoomRatio = 1f; torchForcesNormal = false // não sobrevive à troca
            if (running) reopenCameraAfterClose()
            else {
                try { session?.close() } catch (e: Exception) {}; session = null
                try { camera?.close() } catch (e: Exception) {}; camera = null
            }
        }
    }

    /** Fecha a câmara atual e reabre curCameraId SÓ quando o HAL confirmar o
     *  close (onClosed) — o único jeito fiável de evitar ERROR_MAX_CAMERAS_IN_USE
     *  ao trocar de câmara no Samsung. Rede de segurança: se o onClosed não vier
     *  em 700ms (raro), abre à mesma. Correr sempre no camHandler. */
    private fun reopenCameraAfterClose() {
        try { session?.stopRepeating() } catch (e: Exception) {}
        try { session?.close() } catch (e: Exception) {}
        session = null
        val cam = camera
        if (cam == null) { if (running) openCamera(); return }
        camera = null
        pendingReopen = true
        try { cam.close() } catch (e: Exception) { pendingReopen = false; if (running) openCamera(); return }
        camHandler.postDelayed({ if (pendingReopen && running) { pendingReopen = false; openCamera() } }, 700)
    }

    /** Liga/desliga o LED. O flash pertence à câmara traseira. 3 casos:
     *  (1) a filmar com a câmara do flash em sessão NORMAL → FLASH_MODE na request;
     *  (2) idem mas em HIGH-SPEED (60fps) → setTorchMode dá CAMERA_IN_USE e o
     *      FLASH_MODE é ignorado, por isso baixa a sessão a normal 30fps enquanto
     *      a lanterna estiver ligada;
     *  (3) a filmar com OUTRA câmara (frontal, sem flash) → o LED traseiro está
     *      livre e liga-se via CameraManager.setTorchMode. */
    fun setTorch(on: Boolean) {
        torchOn = on
        camHandler.post { applyTorch() }
    }

    /** Id da câmara com unidade de flash (traseira), ou null se nenhuma tiver. */
    private fun flashCameraId(): String? = try {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) { null }

    private fun applyTorch() {
        val flashId = flashCameraId() ?: return
        val cur = curCameraId
        val onFlashCam = (cur == flashId)
        val highSpeed = session is android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
        if (onFlashCam) {
            // Se a sessão está forçada a normal SÓ por causa da lanterna e ela já
            // está desligada, restaura o high-speed (o fps pedido volta a 60).
            if (torchForcesNormal && !torchOn) {
                torchForcesNormal = false
                Log.d(TAG, "lanterna off → restaura high-speed (60fps)")
                if (running && camera != null) reopenCameraAfterClose()
                return
            }
            if (highSpeed) {
                // tenta a via barata primeiro; se der CAMERA_IN_USE, baixa a 30fps
                val ok = try {
                    (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(flashId, torchOn); true
                } catch (e: Exception) { Log.w(TAG, "torch high-speed setTorchMode: ${e.message}"); false }
                if (!ok && torchOn && !torchForcesNormal) {
                    torchForcesNormal = true
                    Log.d(TAG, "lanterna @60fps → recria sessão em normal 30fps")
                    if (running && camera != null) reopenCameraAfterClose()
                }
            } else {
                pushRequest() // FLASH_MODE na request da sessão normal
            }
        } else {
            // a filmar com a frontal — o LED traseiro está livre
            try {
                (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(flashId, torchOn)
            } catch (e: Exception) { Log.w(TAG, "setTorchMode(frontal→LED traseiro) falhou: ${e.message}") }
        }
    }

    /** Apaga o LED traseiro ligado pela via independente (setTorchMode enquanto se
     *  filma com a frontal) — fechar a câmara frontal não o apaga sozinho. */
    private fun clearIndependentTorch() {
        val fid = flashCameraId() ?: return
        if (curCameraId != fid) try {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(fid, false)
        } catch (e: Exception) {}
    }

    fun setZoom(ratio: Float) { zoomRatio = ratio.coerceIn(minZoom, maxZoom); pushRequest() }

    /** A SurfaceView do preview mudou de GEOMETRIA (nova resolução com outro
     *  aspeto, ex.: 480p 4:3 ↔ 720p 16:9). O objeto Surface é o MESMO, por isso
     *  o setPreviewSurface saltava fora e a sessão ficava configurada para as
     *  dimensões antigas → imagem deformada. Aqui a sessão é sempre refeita. */
    fun refreshPreviewSurface(s: Surface?) {
        previewSurf = s
        camHandler.post {
            // Pode haver uma REABERTURA em curso (mudar de resolução fecha e reabre
            // a câmara). Nesse caso é ela que cria a sessão — criar aqui usaria um
            // device já fechado ("CameraDevice was already closed"). O device é
            // relido AGORA, não capturado antes do post, pela mesma razão.
            if (!running || pendingReopen) return@post
            val cam = camera ?: return@post
            val ch = charsRef ?: return@post
            try { session?.close() } catch (e: Exception) {}
            session = null
            createSession(cam, ch)
        }
    }

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

    /** Re-issues the repeating request with the current torch/zoom state.
     *  Numa sessão high-speed o setRepeatingRequest é ilegal (só setRepeatingBurst)
     *  e os controlos manuais são ignorados — não faz nada nesse caso. */
    private fun pushRequest() {
        val s = session ?: return
        if (s is android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) return
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
        Log.d(TAG, "restartVideoEncoder ${width}x${height} → ${newW}x${newH}")
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
            // Para a sessão mas MANTÉM o handle da câmara — o close é feito de forma
            // gated no passo 3 (reopenCameraAfterClose) para evitar MAX_CAMERAS.
            try { session?.stopRepeating() } catch (e: Exception) {}
            try { session?.close() } catch (e: Exception) {}
            session = null
            clearIndependentTorch()
            torchOn = false; zoomRatio = 1f; torchForcesNormal = false
            screenProj = newProj
            curCameraId = newCamId
            // 2) encoder novo se as dimensões mudarem
            try {
                if (newW != width || newH != height) restartVideoEncoder(newW, newH)
            } catch (e: Exception) { onError("Encoder restart failed: ${e.message}"); return@post }
            // 3) ligar a fonte nova
            if (newProj != null) {
                try { camera?.close() } catch (e: Exception) {}; camera = null
                attachScreenSource()
            } else if (running) {
                reopenCameraAfterClose() // fecha a câmara atual e reabre a nova sem MAX_CAMERAS
            } else {
                try { camera?.close() } catch (e: Exception) {}; camera = null
            }
        }
    }

    /** CÂMARA OFF (meeting): fecha a fonte de vídeo atual (câmara/projection —
     *  LED apaga) e o encoder fica idle; o áudio continua a publicar. Religar
     *  = setVideoSource(camId, null, w, h). */
    fun stopVideoSource() {
        camHandler.post {
            try { virtualDisplay?.surface = null } catch (e: Exception) {}
            try { virtualDisplay?.release() } catch (e: Exception) {}
            virtualDisplay = null
            val oldProj = screenProj
            screenProj = null
            if (oldProj != null) { try { oldProj.stop() } catch (e: Exception) {} }
            clearIndependentTorch()
            try { session?.close() } catch (e: Exception) {}
            session = null
            try { camera?.close() } catch (e: Exception) {}
            camera = null
        }
    }

    /** Password de encriptação (formato Pombo) — definir ANTES de start(). */
    @Volatile var encPassword: String? = null

    /** Teto de fps do encoder (0 = sem teto, ~painel; ex. 30 para limitar a
     *  partilha de ecrã). Definir ANTES de start(); em pleno live usar setMaxFps. */
    @Volatile var maxFps: Int = 0

    /** Muda o teto de fps em pleno broadcast — reinicia o encoder de vídeo.
     *  O encoder novo traz uma SURFACE NOVA, por isso a fonte tem de ser reatada:
     *  o ecrã reata o VirtualDisplay; a câmara precisa de uma SESSÃO nova (a antiga
     *  aponta para a surface já libertada → o encoder ficava sem imagens). */
    fun changeMaxFps(fps: Int) {
        if (fps == maxFps) return
        maxFps = fps
        camHandler.post {
            if (vCodec == null) return@post
            val vd = virtualDisplay
            try {
                if (vd != null) {
                    vd.surface = null
                    restartVideoEncoder(width, height)
                    vd.surface = encoderSurface
                } else {
                    // CÂMARA: fechar a sessão ANTES de largar a surface e reabrir
                    // depois — a sessão nova aponta para a surface nova e o fps
                    // pedido é reavaliado (60 volta a tentar high-speed).
                    try { session?.stopRepeating() } catch (e: Exception) {}
                    try { session?.close() } catch (e: Exception) {}
                    session = null
                    restartVideoEncoder(width, height)
                    if (running && camera != null) reopenCameraAfterClose()
                }
            } catch (e: Exception) { onError("FPS cap failed: ${e.message}") }
        }
    }

    /** Todos os publishes de média passam aqui: com password, o payload inteiro
     *  (container) é selado [0xC2][salt‖iv‖ct] — 1 AES por mensagem. */
    private fun pubSealed(kind: String, payload: ByteArray) {
        val p = encPassword
        bridge.publish(kind, if (p.isNullOrEmpty()) payload else PomboCrypto.seal(payload, p))
    }

    /** Mensagem de CONTROLO (ex.: câmara on/off na chamada) publicada na partição
     *  de áudio — flui mesmo com o vídeo cortado. O plaintext começa em 0xC3, que
     *  distingue de contentores Wire (0xC1 raw / prefixo de tamanho); o envelope de
     *  encriptação continua a ser 0xC2, aplicado por cima. */
    fun publishControl(json: String) {
        pubSealed(kindAudio, byteArrayOf(0xC3.toByte()) + json.toByteArray(Charsets.UTF_8))
    }

    fun start() {
        running = true
        // o KDF (310k iterações) corre AGORA em background — não no 1º frame
        encPassword?.let { p -> thread(name = "enc-kdf") { PomboCrypto.prederive(p) } }
        try {
            startVideoEncoder()
        } catch (e: Exception) {
            onError("H.264 encoder failed: ${e.message}")
            return
        }
        if (startWithVideo) {
            if (screenProj != null) {
                if (!attachScreenSource()) return
            } else {
                openCamera()
            }
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
                if (enc > 0) camFatalRetries = 0 // recuperado: repõe o orçamento de retries
                lastEnc = bEnc; lastPub = bPub; lastMsg = bMsg
                val acked = bridge.ackedTotal()
                val txMbps = (acked - lastAcked) * 8 / 1e6
                lastAcked = acked
                val kf = if (bKfBytes > 0) " · kf=${bKfBytes / 1024}KB×$bKfFrags" else ""
                val br = "%.1f".format(curBitrate / 1e6)
                val fly = bridge.inFlight() / 1024
                onMeter(txMbps, curBitrate / 1e6, enc)
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
        } else if (++calmSecs >= 10 && curBitrate < bitrateCeiling) {
            calmSecs = 0
            curBitrate = Math.min(bitrateCeiling, (curBitrate * 1.15).toInt() + 50_000)
            try {
                vCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, curBitrate) })
            } catch (e: Exception) {}
        }
    }

    private fun startVideoEncoder() {
        // teto de fps: partilha de ecrã com um VirtualDisplay entrega frames à
        // cadência de refrescamento do painel (até 60/90/120Hz) sempre que o
        // conteúdo muda → o encoder pode emitir 60fps. maxFps>0 aplica
        // KEY_MAX_FPS_TO_ENCODER (descarta o excesso à ENTRADA do encoder).
        val fr = if (maxFps > 0) maxFps else 30
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fr)
            if (maxFps > 0 && android.os.Build.VERSION.SDK_INT >= 29)
                try { setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, maxFps.toFloat()) } catch (e: Exception) {}
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
        // Todo o acesso ao serviço de câmara pode lançar se o HAL tiver morrido
        // (device NOT_PRESENT) — apanhar em vez de crashar o processo.
        val id: String?
        val chars: CameraCharacteristics
        try {
            id = (curCameraId?.takeIf { it in mgr.cameraIdList }) ?: mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull()
            if (id == null) { onError("No camera available."); return }
            curCameraId = id
            chars = mgr.getCameraCharacteristics(id)
        } catch (e: Exception) {
            onError("Camera unavailable: ${e.message}")
            return
        }
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
                override fun onDisconnected(cam: CameraDevice) { if (camera === cam) camera = null; cam.close() }
                // Confirmação do HAL de que a câmara libertou — só agora é seguro
                // reabrir (troca de câmara / fallback de high-speed) sem MAX_CAMERAS.
                override fun onClosed(cam: CameraDevice) {
                    // pequena folga p/ o HAL assentar antes de reabrir (recupera de
                    // wedge de high-speed e evita corridas de open/close no Samsung).
                    if (pendingReopen) { pendingReopen = false; if (running) camHandler.postDelayed({ if (running) openCamera() }, 200) }
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    if (camera === cam) camera = null
                    val camId = curCameraId
                    // Qualquer erro de câmara desliga high-speed em toda a sessão: uma
                    // tentativa falhada deixa o HAL instável e propaga erros 3/4 mesmo
                    // à câmara que funciona. A partir daqui, tudo em normal 30fps.
                    hsGloballyDisabled = true
                    if (hsAttempting && camId != null) {
                        // erro provável da config high-speed → reabrir (gated) em normal
                        hsAttempting = false
                        hsFailedCams.add(camId)
                        Log.w(TAG, "camera error $error durante high-speed → reabrir em normal 30fps")
                        pendingReopen = running
                        cam.close() // onClosed reabre já sem high-speed
                    } else if (running && camFatalRetries < 2) {
                        // erro fora de high-speed (HAL ainda a recuperar do wedge) →
                        // uma reabertura gated (já em normal) em vez de desistir.
                        camFatalRetries++
                        Log.w(TAG, "camera error $error → tentativa de recuperação ${camFatalRetries}/2 (normal 30fps)")
                        pendingReopen = true
                        cam.close()
                    } else { cam.close(); onError("Camera error: $error") }
                }
            }, camHandler)
        } catch (e: Exception) { onError("No camera access: ${e.message}") }
    }

    private fun createSession(cam: CameraDevice, chars: CameraCharacteristics) {
        val wantFps = if (maxFps > 0) maxFps else 30
        // 60fps: o modo NORMAL deste sensor topa a 30 (CONTROL_AE_AVAILABLE_TARGET
        // _FPS_RANGES). O verdadeiro 60/120fps vem da sessão CONSTRAINED HIGH-SPEED
        // (o que a câmara nativa usa). Se o dispositivo a suporta nesta resolução
        // até ao alvo, usa-a; qualquer falha faz fallback à sessão normal (30).
        val camId = curCameraId
        // High-speed SÓ em câmaras traseiras: a frontal deste (e da maioria dos)
        // telemóvel não tem high-speed video utilizável e a tentativa CRASHA o
        // serviço de câmara (device morre, tudo NOT_PRESENT). A frontal usa sempre
        // sessão normal (30fps) — sem crash. A traseira mantém o 60fps real.
        val hsRange = if (wantFps > 30 && !facingFront && !hsGloballyDisabled && !torchForcesNormal
                && camId !in hsFailedCams)
            highSpeedRange(chars, wantFps) else null
        if (hsRange != null) { hsAttempting = true; createHighSpeedSession(cam, chars, hsRange) }
        else { hsAttempting = false; createNormalSession(cam, chars, wantFps) }
    }

    /** Intervalo high-speed que chega ao alvo nesta resolução, ou null. */
    private fun highSpeedRange(chars: CameraCharacteristics, wantFps: Int): Range<Int>? = try {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = android.util.Size(width, height)
        val sizes = map?.highSpeedVideoSizes?.toList().orEmpty()
        Log.d(TAG, "high-speed sizes=$sizes (querendo ${width}x${height} @${wantFps})")
        if (map == null || !sizes.contains(size)) null
        else {
            val rs = map.getHighSpeedVideoFpsRangesFor(size).toList()
            Log.d(TAG, "high-speed ranges @${width}x${height}=$rs (want=$wantFps)")
            // Este sensor só entrega frames com ranges FIXOS (lower==upper): o
            // variável [30,120] configura mas não emite nada (captado pelo watchdog).
            // Preferir o range FIXO com menor upper que ainda chega ao alvo — 120fps
            // para want=60; o encoder (KEY_MAX_FPS_TO_ENCODER) baixa a 60 de forma
            // estável. Só se não houver fixo é que se aceita um variável.
            rs.firstOrNull { it.lower == wantFps && it.upper == wantFps }
                ?: rs.filter { it.lower == it.upper && it.upper >= wantFps }.minByOrNull { it.upper }
                ?: rs.filter { it.upper >= wantFps }.minByOrNull { it.upper }
        }
    } catch (e: Exception) { Log.w(TAG, "high-speed query erro: ${e.message}"); null }

    @Suppress("DEPRECATION")
    private fun createNormalSession(cam: CameraDevice, chars: CameraCharacteristics, wantFps: Int) {
        val enc = encoderSurface ?: return
        val targets = listOfNotNull(previewSurf, enc)
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        // ALCANÇAR o fps escolhido: (1) intervalo BLOQUEADO no alvo (estável);
        // (2) senão o que CHEGA ao alvo com maior lower (atinge o alvo com luz
        // suficiente, cai o mínimo em pouca luz — evita o [7,60] a colapsar a 8fps);
        // (3) senão o locked mais alto; (4) senão o de maior upper.
        val fpsRange =
            ranges.firstOrNull { it.lower == wantFps && it.upper == wantFps }
            ?: ranges.filter { it.upper >= wantFps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.lower == it.upper }.maxByOrNull { it.upper }
            ?: ranges.maxByOrNull { it.upper }
            ?: Range(wantFps, wantFps)
        Log.d(TAG, "AE fps ranges=$ranges → escolhido=$fpsRange (want=$wantFps)")
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
                    catch (e: Exception) {
                        // Uma sessão ANTIGA a reconfigurar-se depois de fechada é o
                        // rasto normal de uma troca de resolução/câmara — a sessão
                        // nova já está a emitir. Não é erro para o utilizador.
                        if (pendingReopen || session !== s || e.message?.contains("has been closed") == true)
                            Log.w(TAG, "setRepeatingRequest ignorado: ${e.message}")
                        else onError("Capture failed: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (pendingReopen) Log.w(TAG, "sessão falhou durante reabertura (ignorado)")
                    else onError("Camera session failed.")
                }
            }, camHandler)
        } catch (e: Exception) {
            // device fechado a meio de uma reabertura é ESPERADO (troca de câmara /
            // resolução): a reabertura cria a sessão certa — não incomodar o user.
            if (pendingReopen || e.message?.contains("already closed") == true)
                Log.w(TAG, "sessão ignorada: ${e.message}")
            else onError("Camera session: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createHighSpeedSession(cam: CameraDevice, chars: CameraCharacteristics, range: Range<Int>) {
        val enc = encoderSurface ?: return
        val targets = listOfNotNull(previewSurf, enc)
        // Marca esta câmara como sem high-speed e volta à sessão normal (30fps).
        // Cobre os dois modos de falha: (a) sessão/erro no arranque, (b) sessão
        // que configura mas nunca entrega frames (frontal) — apanhada pelo watchdog.
        fun fallback(reason: String, closing: CameraCaptureSession?) {
            hsAttempting = false
            hsGloballyDisabled = true // quebra-cascata: nunca mais high-speed nesta sessão
            curCameraId?.let { hsFailedCams.add(it) }
            Log.w(TAG, "high-speed fallback ($reason) → reabrir câmara em normal 30fps")
            closing?.let { try { it.stopRepeating() } catch (x: Exception) {}; try { it.close() } catch (x: Exception) {} }
            if (session === closing) session = null
            // Reabrir a câmara por completo (gated no onClosed) — reutilizar o mesmo
            // device logo após uma sessão high-speed deixa o HAL instável (erro 3/4
            // e "camera session failed"). A câmara já está marcada → abre em normal.
            if (running && camera === cam) reopenCameraAfterClose()
        }
        try {
            cam.createConstrainedHighSpeedCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) return
                    try {
                        val hs = s as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            previewSurf?.let { addTarget(it) }
                            addTarget(enc)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        }
                        reqBuilder = req  // high-speed ignora a maioria dos controlos manuais
                        val list = hs.createHighSpeedRequestList(req.build())
                        session = s
                        hs.setRepeatingBurst(list, null, camHandler)
                        Log.d(TAG, "high-speed session ativa @$range (${width}x${height})")
                        // watchdog: sem frames codificados em ~2s → a sessão configura
                        // mas não entrega (frontal). Cai para a sessão normal.
                        val encBefore = bEnc
                        camHandler.postDelayed({
                            if (running && session === s && bEnc == encBefore)
                                fallback("sem frames em 2s", s)
                            else if (session === s) hsAttempting = false // confirmado a funcionar
                        }, 2000)
                    } catch (e: Exception) { fallback("request: ${e.message}", s) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { fallback("configure failed", s) }
            }, camHandler)
        } catch (e: Exception) { fallback("não suportado: ${e.message}", null) }
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
                // Um restart do encoder (mudança de fps/resolução em pleno live)
                // pára este codec ENTRE o dequeue e o release. É esperado que o loop
                // termine aqui — mas tem de sair LIMPO: sem isto, a IllegalState
                // rebentava a thread "video-enc" e levava a app abaixo.
                try {
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
                } catch (e: Exception) { break }
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
            for (r in records) { pubSealed(kindVideo, Wire.container(listOf(r))); bMsg++ }
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
            // fps/bitrate REAIS da sessão (podem ter mudado no menu em sessão) —
            // estavam fixos em 30 e no bitrate do construtor, o que fazia o viewer
            // anunciar valores errados depois de uma alteração ao vivo.
            put("framerate", if (maxFps > 0) maxFps else 30)
            put("bitrate", baseBitrate)
            put("rotation", contentRotation())
        })
    }.toString()

    private fun flushVBatch() = synchronized(vBatch) { flushVBatchLocked() }
    private fun flushVBatchLocked() {
        if (vBatch.isEmpty()) return
        val recs = ArrayList(vBatch); vBatch.clear()
        pubSealed(kindVideo, Wire.container(recs))
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
                setInteger(MediaFormat.KEY_BIT_RATE, curAudioBitrate)
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
        while (running && !aRestart) { // aRestart = mudança de bitrate de áudio
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
        pubSealed(if (singlePartition) kindVideo else kindAudio, Wire.container(recs))
    }

    fun stop() {
        running = false
        clearIndependentTorch() // apaga o LED traseiro se estava ligado via setTorchMode
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
