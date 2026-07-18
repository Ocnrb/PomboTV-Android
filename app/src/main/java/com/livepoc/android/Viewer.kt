package com.livepoc.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native viewer — full parity with the live-poc.html playback engine:
 *  - encoded jitter buffers (sorted) fed by the Streamr bridge
 *  - AUDIO is the master clock; video maps onto it via the measured A/V offset
 *  - ADAPTIVE buffer target: grows ×1.2 on each underrun (bursty delivery),
 *    decays back when calm — the fixed-target version stuttered under jitter
 *  - join-burst settle gate: on subscribe the overlay releases buffered
 *    messages faster than realtime; anchoring mid-burst causes fast-forward
 *    then starvation. Wait until audio arrives at ~realtime (≤1.4×, max 3s).
 *  - live re-anchor when latency runs away (edge > target + 700ms)
 *  - render pacing HOLDS each decoded frame until due (releasing frames with
 *    far-future timestamps floods the SurfaceView BufferQueue (~3 buffers) and
 *    wedges the decoder — the cause of the original freezes)
 *  - silent-wedge detector: input flowing but zero output for 2s → rebuild
 */
class Viewer(
    private val bridge: StreamrBridge,
    videoSurface: Surface,
    // chamada 1:1: kinds rv/ra (partições do par, via bridgeCallStart) e buffer
    // alvo mais curto (a latência conversacional manda)
    private val kindVideo: String = "video",
    private val kindAudio: String = "audio",
    baseTargetMs: Int = 300, // watch (live 1→N); meeting/call passam 180 (conversacional)
    // meeting/call: áudio de VOZ (USAGE_VOICE_COMMUNICATION) para casar com o
    // mic VOICE_COMMUNICATION — senão, com o mic ativo, o Android roteia a
    // reprodução USAGE_MEDIA para o AURICULAR (parecia "sem som"). O watch
    // (sem mic) fica em USAGE_MEDIA normal.
    private val commAudio: Boolean = false,
    private val onState: (String) -> Unit,
    private val onStats: (String) -> Unit,
    private val onVideoSize: (w: Int, h: Int) -> Unit = { _, _ -> },
    private val onMeter: (rxMbps: Double, fps: Int, brMbps: Double) -> Unit = { _, _, _ -> },
    /** Estado da câmara do emissor (mensagem de controlo 0xC3 na chamada). */
    private val onCamState: (on: Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "Viewer"
        // teto do buffer adaptativo (era 1.5s) — cap do pior caso mais curto para
        // a imagem não ficar muito atrasada quando a rede tem picos de jitter
        private const val MAX_TARGET_US = 1_000_000.0
        private const val V_LOOKAHEAD_US = 250_000.0 // feed decoder this far ahead
        private const val SAMPLE_RATE = 48000
        private const val US_PER_FRAME = 1e6 / SAMPLE_RATE
    }

    private val baseTargetUs = baseTargetMs * 1000.0
    private val startMinUs = minOf(baseTargetUs, 350_000.0) // modest start cushion

    @Volatile private var running = true

    private val vAsm = Wire.Assembler()
    private val aAsm = Wire.Assembler()
    private val lock = java.lang.Object()
    private val vPending = ArrayList<Wire.Frame>()
    private val aPending = ArrayList<Wire.Frame>()

    @Volatile private var sawAudio = false
    @Volatile private var sawVideo = false
    @Volatile private var newestVideoTs = 0.0
    @Volatile private var newestAudioTs = 0.0
    @Volatile private var avOffsetUs: Double? = null
    @Volatile private var effTargetUs = baseTargetUs

    // audio master clock: clock = writtenMediaUs − (written−played)·usPerFrame.
    // Re-anchoring is a clean jump of writtenMediaUs (no silence dragging).
    private var track: AudioTrack? = null
    @Volatile private var audioAnchored = false
    @Volatile private var writtenMediaUs = 0.0
    @Volatile private var writtenFrames = 0L
    // video-only fallback clock
    @Volatile private var vWallAnchorNs = 0L
    @Volatile private var vWallAnchorTsUs = 0.0
    // silent-wedge detector
    @Volatile private var vRebuildRequested = false
    @Volatile private var curRotation = 0
    @Volatile private var curCodedW = 0
    @Volatile private var curCodedH = 0
    @Volatile private var volume = 1f
    @Volatile private var paused = false

    /** Pausa de LIVE: para de consumir; os buffers ficam aparados na borda e o
     *  catch-up existente re-ancora no presente ao retomar (não acumula atraso). */
    fun setPaused(p: Boolean) {
        paused = p
        synchronized(lock) { lock.notifyAll() }
    }
    // Detachable video surface (background support): null while the app is
    // hidden — audio keeps playing, video decode pauses and rebuilds on the
    // next config keyframe when the surface returns.
    @Volatile private var videoSurface: Surface? = videoSurface

    fun setVideoSurface(s: Surface?) {
        if (videoSurface === s) return
        videoSurface = s
        vRebuildRequested = true
        synchronized(lock) { lock.notifyAll() }
    }

    /** 0..1 — aplicado ao AudioTrack atual e aos futuros (rebuilds). */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try { track?.setVolume(volume) } catch (e: Exception) {}
    }

    @Volatile private var stVMsg = 0
    @Volatile private var stVFrm = 0
    @Volatile private var stVDec = 0
    @Volatile private var stAFrm = 0
    @Volatile private var stUnder = 0
    @Volatile private var resLabelSet = false // depois do decoder pôr "WxH · Mbps", gates não sobrescrevem
    @Volatile private var cfgBrMbps = -1.0    // bitrate anunciado pelo emissor (medidor)
    @Volatile private var lastAudioArrivalNs = 0L // frescura do áudio (gaps de screen-share)
    @Volatile private var lastRenderNs = 0L       // último frame realmente renderizado (vAge)
    @Volatile private var rxBytes = 0L            // débito de chegada — encontra o teto do caminho
    // latência E2E: âncora (wallMs, ts) do último keyframe com config — no
    // render: e2e = agora − kfWall − (tsFrame − tsKf). Requer relógios ~NTP.
    @Volatile private var kfWallMs = 0L
    @Volatile private var kfTsUs = 0.0
    @Volatile private var e2eMs = -1
    // menor e2e bruto já visto: serve de linha de base para o e2e RELATIVO, que
    // é imune a relógios desalinhados entre emissor e viewer.
    @Volatile private var e2eMinMs = Int.MAX_VALUE

    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var statsThread: Thread? = null

    fun start() {
        onState("waiting for signal…")
        bridge.subscribe(kindVideo)
        bridge.subscribe(kindAudio)
        vThread = thread(name = "video-dec") { videoLoop() }
        aThread = thread(name = "audio-dec") { audioLoop() }
        statsThread = thread(name = "viewer-stats") { statsLoop() }
    }

    /** Password de encriptação (formato Pombo) — pode ser definida/corrigida
     *  em pleno play: o payload seguinte já decifra. */
    @Volatile var encPassword: String? = null
    private var lastEncWarnNs = 0L
    private fun encWarn(msg: String) {
        val now = System.nanoTime()
        if (now - lastEncWarnNs > 2_000_000_000L) { lastEncWarnNs = now; onState(msg) }
    }

    /** Bridge callback — background thread. */
    fun onMessage(kind: String, payload: ByteArray) {
        if (!running) return
        rxBytes += payload.size
        var data = payload
        if (PomboCrypto.isSealed(data)) {
            val pass = encPassword
            if (pass.isNullOrEmpty()) { encWarn("encrypted stream — password needed"); return }
            // 1ª mensagem selada deriva a chave (PBKDF2 310k, algumas centenas de
            // ms UMA vez neste thread da ponte); depois é cache + AES-GCM (µs)
            data = PomboCrypto.open(data, pass) ?: run { encWarn("encrypted stream — wrong password"); return }
        }
        // mensagem de CONTROLO (0xC3): câmara on/off do emissor na chamada — não é
        // média Wire, trata e sai antes do demux de registos
        if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == 0xC3) {
            try {
                val o = JSONObject(String(data, 1, data.size - 1, Charsets.UTF_8))
                if (o.optString("t") == "cam") onCamState(o.optBoolean("on", true))
            } catch (e: Exception) {}
            return
        }
        Wire.forEachRecord(data) { rec ->
            // demux POR REGISTO: nos modos single-partition/mux o áudio viaja na
            // partição de vídeo com FLAG_AUD — o recetor auto-deteta, qualquer
            // que seja o modo do emissor
            if (kind == kindVideo && !Wire.isAudioRecord(rec)) {
                stVMsg++
                val f = vAsm.add(rec) ?: return@forEachRecord
                stVFrm++; sawVideo = true
                if (f.timestampUs > newestVideoTs) newestVideoTs = f.timestampUs
                synchronized(lock) {
                    insSorted(vPending, f)
                    // bound memory mesmo com o consumidor parado (anomalias de
                    // relógio A/V não podem deixar o buffer crescer sem limite)
                    while (vPending.size > 1 && spanUs(vPending) > 3_500_000) vPending.removeAt(0)
                    lock.notifyAll()
                }
            } else {
                val f = aAsm.add(rec) ?: return@forEachRecord
                stAFrm++; sawAudio = true
                lastAudioArrivalNs = System.nanoTime()
                if (f.timestampUs > newestAudioTs) newestAudioTs = f.timestampUs
                synchronized(lock) {
                    insSorted(aPending, f)
                    while (aPending.size > 1 && spanUs(aPending) > 5_000_000) aPending.removeAt(0)
                    lock.notifyAll()
                }
            }
        }
        updateAvOffset()
    }

    private fun insSorted(buf: ArrayList<Wire.Frame>, item: Wire.Frame) {
        var lo = 0; var hi = buf.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (buf[m].timestampUs < item.timestampUs) lo = m + 1 else hi = m
        }
        // dedup por timestamp: entregas duplicadas (subs zombie/redundantes)
        // tocavam cada pacote 2× — áudio a meia velocidade ("câmara lenta")
        if (lo < buf.size && buf[lo].timestampUs == item.timestampUs) return
        buf.add(lo, item)
    }

    private fun spanUs(buf: ArrayList<Wire.Frame>): Double =
        if (buf.size > 1) buf.last().timestampUs - buf.first().timestampUs else 0.0

    private fun updateAvOffset() {
        if (newestVideoTs == 0.0 || newestAudioTs == 0.0) return
        // Áudio com gaps (screen-share em silêncio não emite pacotes): raw infla
        // artificialmente, o vClock saltava e o vídeo congelava com playing=true.
        // Com áudio parado o offset FICA CONGELADO — o relógio de áudio continua
        // a avançar em tempo real via silêncio, e o vídeo segue normalmente.
        if (avOffsetUs != null && System.nanoTime() - lastAudioArrivalNs > 800_000_000L) return
        val raw = newestVideoTs - newestAudioTs
        val cur = avOffsetUs
        avOffsetUs = if (cur == null || abs(raw - cur) > 1_000_000) raw else cur + (raw - cur) * 0.05
    }

    // ---------------- clocks ----------------
    private fun audioClockUs(): Double {
        val t = track ?: return Double.NaN
        if (!audioAnchored) return Double.NaN
        val bufferedUs = (writtenFrames - t.playbackHeadPosition.toLong()) * US_PER_FRAME
        return writtenMediaUs - max(0.0, bufferedUs)
    }

    private fun videoClockUs(): Double {
        val a = audioClockUs()
        if (!a.isNaN()) return a + (avOffsetUs ?: 0.0)
        if (vWallAnchorNs != 0L) return vWallAnchorTsUs + (System.nanoTime() - vWallAnchorNs) / 1000.0
        return Double.NaN
    }

    // ---------------- video ----------------
    private fun videoLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        var codec: MediaCodec? = null
        var avccNalLen = 0
        var waitingKey = true
        var futureSinceNs = 0L // há quanto tempo o head está "no futuro" (anomalia de offset)
        val info = MediaCodec.BufferInfo()
        val startWall = SystemClock.elapsedRealtime()

        while (running) {
            if (vRebuildRequested) {
                vRebuildRequested = false
                try { codec?.release() } catch (e: Exception) {}
                codec = null; waitingKey = true
            }
            // sem surface (app em background): áudio segue como relógio, o vídeo
            // espera; buffers continuam limitados por catchUp/caps de span
            if (videoSurface == null || paused) {
                synchronized(lock) { catchUpLocked(); lock.wait(100) }
                continue
            }
            // video-only fallback anchor
            if (!audioAnchored && !sawAudio && vWallAnchorNs == 0L && sawVideo
                && SystemClock.elapsedRealtime() - startWall > 1500) {
                synchronized(lock) {
                    if (spanUs(vPending) >= startMinUs) {
                        vWallAnchorTsUs = vPending.last().timestampUs - effTargetUs
                        vWallAnchorNs = System.nanoTime()
                    }
                }
            }

            val needConfig = codec == null
            val frame = synchronized<Wire.Frame?>(lock) {
                catchUpLocked()
                val clock = videoClockUs()
                when {
                    vPending.isEmpty() -> { futureSinceNs = 0L; lock.wait(50); null }
                    needConfig -> {
                        // only a keyframe carrying config can start the decoder;
                        // anything before it is undecodable — discard
                        val f = vPending.removeAt(0)
                        if (f.config != null && f.key) f else null
                    }
                    clock.isNaN() -> { lock.wait(50); null }
                    vPending[0].timestampUs <= clock + V_LOOKAHEAD_US -> { futureSinceNs = 0L; vPending.removeAt(0) }
                    else -> {
                        // Head persistentemente "no futuro" = offset/relógio errado
                        // (gaps de áudio, bases distintas). Re-snap: alinhar o
                        // offset para o head ser devido AGORA e retomar o vídeo.
                        val now = System.nanoTime()
                        if (futureSinceNs == 0L) futureSinceNs = now
                        else if (now - futureSinceNs > 700_000_000L) {
                            val a = audioClockUs()
                            if (!a.isNaN()) avOffsetUs = vPending[0].timestampUs - a
                            else if (vWallAnchorNs != 0L) { vWallAnchorTsUs = vPending[0].timestampUs; vWallAnchorNs = now }
                            futureSinceNs = 0L
                        }
                        lock.wait(15); null
                    }
                }
            } ?: continue

            // âncora de latência E2E (wallMs segue em todos os keyframes)
            if (frame.key && frame.config != null) {
                val w = frame.config.optLong("wallMs", 0)
                if (w > 0) { kfWallMs = w; kfTsUs = frame.timestampUs }
            }
            // Formato mudou em pleno live (rotação do telemóvel, ou a FONTE
            // redimensionou — ex.: barra de partilha do tab) → rebuild com este
            // keyframe (rotação vive no decoder; tamanho define caixa/decoder).
            if (codec != null && frame.key && frame.config != null) {
                val r = frame.config.optJSONObject("streamSettings")?.optInt("rotation", 0) ?: 0
                val cw = frame.config.optInt("codedWidth", 0)
                val ch = frame.config.optInt("codedHeight", 0)
                if (r != curRotation || (cw > 0 && cw != curCodedW) || (ch > 0 && ch != curCodedH)) {
                    try { codec.release() } catch (e: Exception) {}
                    codec = null
                }
            }
            if (codec == null) {
                val cfg = frame.config ?: continue
                try {
                    val built = buildVideoDecoder(cfg, frame)
                    codec = built.first; avccNalLen = built.second
                    waitingKey = false // frame IS the config keyframe — feed it
                    resLabelSet = true
                    // estável = ecrã limpo: a resolução/bitrate vivem no medidor do
                    // topo, não por cima do vídeo (resLabelSet trava os gates).
                    onState("")
                    // bitrate ANUNCIADO pelo emissor — métrica do medidor
                    cfg.optJSONObject("streamSettings")?.let { cfgBrMbps = it.optInt("bitrate") / 1e6 }
                } catch (e: Exception) {
                    Log.e(TAG, "decoder cfg", e); continue
                }
            } else if (waitingKey) {
                if (frame.key) waitingKey = false else continue
            }

            val c = codec
            val data = if (avccNalLen > 0) AvcUtils.avccToAnnexB(frame.data, avccNalLen) else frame.data
            try {
                // Feed com escape: um decoder ENCRAVADO deixa de devolver input
                // buffers e este loop prendia o thread para sempre — o pedido de
                // rebuild do detetor de wedge nunca era consumido (imagem congelada
                // com playing=true). Agora: respeita o pedido e tem watchdog próprio.
                var fed = false
                val feedStartMs = SystemClock.elapsedRealtime()
                while (running && !fed && !vRebuildRequested) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = c.getInputBuffer(inIdx)!!
                        ib.clear(); ib.put(data)
                        c.queueInputBuffer(inIdx, 0, data.size, frame.timestampUs.toLong(), 0)
                        fed = true
                    }
                    drainAndRender(c, info)
                    if (!fed && SystemClock.elapsedRealtime() - feedStartMs > 3000) {
                        Log.w(TAG, "decoder não aceita input há 3s → rebuild")
                        vRebuildRequested = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "video decode — rebuilding", e)
                try { c.release() } catch (x: Exception) {}
                codec = null; waitingKey = true
            }
        }
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
    }

    /**
     * Drain decoder output, HOLDING each frame until its presentation time.
     * Never queue far-future frames to the surface: the SurfaceView BufferQueue
     * only holds ~3 buffers — flooding it stalls the decoder (frozen video).
     */
    private fun drainAndRender(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) return
            stVDec++
            // HOLD até à hora do frame — mas com teto: o offset A/V pode oscilar
            // segundos/horas no arranque (bases de ecrã vs micro) e um "delay"
            // gigante bloqueava este thread para sempre (buffer 26s, decode ~0).
            var waitedMs = 0L
            while (running && waitedMs < 250) {
                val clock = videoClockUs()
                val delayUs = if (clock.isNaN()) 0.0 else info.presentationTimeUs - clock
                if (delayUs <= 20_000) break
                SystemClock.sleep(10); waitedMs += 10
            }
            val clock = videoClockUs()
            val lateUs = if (clock.isNaN()) 0.0 else clock - info.presentationTimeUs
            val render = lateUs <= 120_000
            if (render) {
                lastRenderNs = System.nanoTime()
                // Latência E2E deste frame (captura→render) pela âncora do keyframe.
                // ATENÇÃO: compara o NOSSO relógio de parede com o do EMISSOR, por
                // isso qualquer desfasamento entre máquinas entra inteiro no valor
                // (já se mediu ~6,9s entre telemóvel e PC neste projeto). Guardamos
                // o BRUTO e o mínimo histórico: a diferença cancela o desvio fixo.
                if (kfWallMs > 0) {
                    val lat = (System.currentTimeMillis() - kfWallMs -
                        ((info.presentationTimeUs - kfTsUs) / 1000.0)).toInt()
                    e2eMs = if (e2eMs < 0) lat else (e2eMs * 7 + lat) / 8
                    if (lat < e2eMinMs) e2eMinMs = lat // melhor caso observado
                }
            }
            c.releaseOutputBuffer(idx, render) // drop if hopelessly late
        }
    }

    /** Drop to the most recent keyframe when the encoded buffer runs away (>2.5s). */
    private fun catchUpLocked() {
        if (spanUs(vPending) <= 2_500_000) return
        var lastKey = -1
        for (i in vPending.indices.reversed()) if (vPending[i].key) { lastKey = i; break }
        if (lastKey > 0) {
            repeat(lastKey) { vPending.removeAt(0) }
            if (audioClockUs().isNaN() && vWallAnchorNs != 0L) {
                vWallAnchorTsUs = vPending[0].timestampUs
                vWallAnchorNs = System.nanoTime()
            }
        }
    }

    /** Returns (codec, avccNalLengthSize); 0 = Annex-B stream. */
    private fun buildVideoDecoder(cfg: JSONObject, firstKey: Wire.Frame): Pair<MediaCodec, Int> {
        val w = cfg.optInt("codedWidth", cfg.optJSONObject("streamSettings")?.optInt("width") ?: 1280)
        val h = cfg.optInt("codedHeight", cfg.optJSONObject("streamSettings")?.optInt("height") ?: 720)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        var nalLen = 0
        val desc = cfg.optJSONArray("description")
        if (desc != null) {
            val bytes = ByteArray(desc.length()) { (desc.getInt(it) and 0xFF).toByte() }
            val avcc = AvcUtils.parseAvcC(bytes)
            if (avcc != null) {
                nalLen = avcc.nalLengthSize
                if (avcc.spsList.isNotEmpty()) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(AvcUtils.withStartCode(avcc.spsList[0])))
                if (avcc.ppsList.isNotEmpty()) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(AvcUtils.withStartCode(avcc.ppsList[0])))
            }
        } else {
            val (sps, pps) = AvcUtils.extractSpsPps(firstKey.data)
            if (sps != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(AvcUtils.withStartCode(sps)))
            if (pps != null) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(AvcUtils.withStartCode(pps)))
        }
        try { fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) } catch (e: Exception) {}
        // Orientação do conteúdo (broadcaster móvel): KEY_ROTATION no decoder
        // roda o output na Surface; o container recebe as dimensões trocadas.
        val rot = cfg.optJSONObject("streamSettings")?.optInt("rotation", 0) ?: 0
        curRotation = rot
        curCodedW = w; curCodedH = h
        if (rot != 0) try { fmt.setInteger(MediaFormat.KEY_ROTATION, rot) } catch (e: Exception) {}
        if (rot == 90 || rot == 270) onVideoSize(h, w) else onVideoSize(w, h)
        val surf = videoSurface ?: throw IllegalStateException("no video surface")
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, surf, null, 0)
        codec.start()
        return Pair(codec, nalLen)
    }

    // ---------------- audio ----------------
    private fun audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        // gate 1: modest cushion of the master
        while (running) {
            val ready = synchronized(lock) {
                if (sawAudio && spanUs(aPending) >= min(effTargetUs, startMinUs)) true
                else { lock.wait(100); false }
            }
            if (ready) break
        }
        if (!running) return

        // gate 2: wait for the join burst to drain (arrival rate ≤ 1.4×), max 3s
        if (!resLabelSet) onState("stabilizing…")
        run {
            val startNs = System.nanoTime()
            var lastTs = newestAudioTs
            var lastNs = startNs
            while (running && System.nanoTime() - startNs < 3_000_000_000L) {
                SystemClock.sleep(350)
                val nowNs = System.nanoTime()
                val rate = (newestAudioTs - lastTs) / ((nowNs - lastNs) / 1000.0)
                lastTs = newestAudioTs; lastNs = nowNs
                if (rate <= 1.4) break
            }
        }
        if (!running) return
        if (!resLabelSet) onState("buffering…")

        var codec: MediaCodec
        try { codec = createOpusDecoder() } catch (e: Exception) {
            Log.e(TAG, "opus decoder", e); return
        }
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (commAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(if (commAudio) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { t.setVolume(volume) } catch (e: Exception) {}
        track = t

        // anchor at newest − target (never earlier than the oldest buffered)
        synchronized(lock) {
            val newest = aPending.last().timestampUs
            val start = max(aPending.first().timestampUs, newest - effTargetUs)
            while (aPending.size > 1 && aPending[0].timestampUs < start) aPending.removeAt(0)
            writtenMediaUs = aPending[0].timestampUs
        }
        t.play()
        audioAnchored = true

        val silence20 = ByteArray(SAMPLE_RATE / 50 * 2)
        val info = MediaCodec.BufferInfo()
        var dry = false
        var lastUnderMs = 0L

        while (running) {
            // pausado: não consumir; aparar o buffer à borda (o catch-up abaixo
            // re-ancora limpo ao retomar)
            if (paused) {
                synchronized(lock) {
                    while (aPending.size > 1 && spanUs(aPending) > effTargetUs) aPending.removeAt(0)
                }
                SystemClock.sleep(60)
                continue
            }
            // latency catch-up: the live edge ran away → clean jump (no dragging)
            val clock = audioClockUs()
            if (!clock.isNaN() && newestAudioTs - clock > effTargetUs + 700_000) {
                synchronized(lock) {
                    val target = newestAudioTs - effTargetUs
                    while (aPending.size > 1 && aPending[0].timestampUs < target) aPending.removeAt(0)
                    val head = aPending.firstOrNull()?.timestampUs
                    if (head != null && head > writtenMediaUs) writtenMediaUs = head
                }
            }

            val pkt = synchronized(lock) { if (aPending.isEmpty()) null else aPending[0] }
            if (pkt == null) {
                // dry: silence keeps the clock (and the video) alive
                val aheadUs = (writtenFrames - t.playbackHeadPosition.toLong()) * US_PER_FRAME
                if (aheadUs < 80_000) {
                    if (!dry) {
                        dry = true; stUnder++
                        lastUnderMs = SystemClock.elapsedRealtime()
                        effTargetUs = min(MAX_TARGET_US, effTargetUs * 1.2 + 60_000)
                    }
                    t.write(silence20, 0, silence20.size)
                    writtenFrames += silence20.size / 2
                    writtenMediaUs += 20_000
                } else SystemClock.sleep(5)
                continue
            }
            dry = false
            // recuperação MAIS RÁPIDA: começa a encolher 2.5s após o último
            // underrun (era 5s) e desce ~250ms/s — a latência acumulada num pico
            // drena depressa em vez de ficar presa em cima
            if (SystemClock.elapsedRealtime() - lastUnderMs > 2500 && effTargetUs > baseTargetUs)
                effTargetUs = max(baseTargetUs, effTargetUs - 5_000) // ~250ms/s decay at 50 pkt/s

            when {
                pkt.timestampUs < writtenMediaUs - 20_000 -> {
                    synchronized(lock) { if (aPending.isNotEmpty() && aPending[0] === pkt) aPending.removeAt(0) }
                }
                pkt.timestampUs > writtenMediaUs + 30_000 -> { // stream gap → bounded fill
                    val gap = min(pkt.timestampUs - writtenMediaUs, 60_000.0)
                    val samples = (gap / 1e6 * SAMPLE_RATE).toInt()
                    val buf = ByteArray(samples * 2)
                    t.write(buf, 0, buf.size)
                    writtenFrames += samples
                    writtenMediaUs += samples * US_PER_FRAME
                }
                else -> {
                    synchronized(lock) { if (aPending.isNotEmpty() && aPending[0] === pkt) aPending.removeAt(0) }
                    val samples = try { decodeAndPlay(codec, t, pkt, info) } catch (e: Exception) {
                        Log.e(TAG, "opus decode — rebuilding", e)
                        try { codec.release() } catch (x: Exception) {}
                        codec = try { createOpusDecoder() } catch (x: Exception) { break }
                        0
                    }
                    writtenFrames += samples
                    writtenMediaUs = pkt.timestampUs +
                        (if (samples > 0) samples * US_PER_FRAME else (if (pkt.durationUs > 0) pkt.durationUs else 20_000.0))
                }
            }
        }
        try { t.stop() } catch (e: Exception) {}
        t.release()
        try { codec.stop() } catch (e: Exception) {}
        codec.release()
    }

    /** Synchronous decode of one opus packet → blocking AudioTrack write. Returns samples written. */
    private fun decodeAndPlay(codec: MediaCodec, t: AudioTrack, pkt: Wire.Frame, info: MediaCodec.BufferInfo): Int {
        var written = 0
        var fed = false
        var tries = 0
        while (running && tries < 8) {
            if (!fed) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val ib = codec.getInputBuffer(i)!!
                    ib.clear(); ib.put(pkt.data)
                    codec.queueInputBuffer(i, 0, pkt.data.size, pkt.timestampUs.toLong(), 0)
                    fed = true
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                if (info.size > 0) {
                    val bb = codec.getOutputBuffer(o)!!
                    val pcm = ByteArray(info.size)
                    bb.position(info.offset); bb.get(pcm)
                    t.write(pcm, 0, pcm.size)
                    written += pcm.size / 2
                }
                codec.releaseOutputBuffer(o, false)
                if (fed && written > 0) break
            } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && fed) tries++
        }
        return written
    }

    private fun createOpusDecoder(): MediaCodec {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray(Charsets.US_ASCII))
        head.put(1); head.put(1)    // version, channels
        head.putShort(0)            // pre-skip
        head.putInt(SAMPLE_RATE)
        head.putShort(0)            // output gain
        head.put(0)                 // mapping family
        head.flip()
        fmt.setByteBuffer("csd-0", head)
        fmt.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN))
        fmt.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(fmt, null, null, 0)
        codec.start()
        return codec
    }

    // ---------------- stats + wedge detector ----------------
    private fun statsLoop() {
        var lastFed = 0; var lastDec = 0; var stallSecs = 0
        var lastRx = 0L
        while (running) {
            SystemClock.sleep(1000)
            if (!running) break
            val rxMbps = (rxBytes - lastRx) * 8 / 1e6
            lastRx = rxBytes
            // silent wedge: frames fed but ZERO decoded in the last second
            val dFed = stVFrm - lastFed; val dDec = stVDec - lastDec
            lastFed = stVFrm; lastDec = stVDec
            onMeter(rxMbps, dDec, cfgBrMbps) // dDec (frames em 1s) É o fps de decode
            if (dFed >= 3 && dDec == 0) stallSecs++ else if (dDec > 0) stallSecs = 0
            if (stallSecs >= 2) {
                stallSecs = 0
                Log.w(TAG, "video decoder wedged → rebuild")
                vRebuildRequested = true
                synchronized(lock) { lock.notifyAll() }
            }
            val vSpan: Double; val aSpan: Double; val vN: Int; val aN: Int
            synchronized(lock) { vSpan = spanUs(vPending); aSpan = spanUs(aPending); vN = vPending.size; aN = aPending.size }
            val vAgeMs = if (lastRenderNs == 0L) -1 else ((System.nanoTime() - lastRenderNs) / 1_000_000).toInt()
            // espelho no logcat: permite medição automatizada (adb) sem UI
            Log.d(TAG, "stats vDec=$stVDec aFrm=$stAFrm rx=${"%.2f".format(rxMbps)} vAge=$vAgeMs e2e=$e2eMs under=$stUnder tgt=${(effTargetUs / 1000).toInt()}")
            // linha de FEED uniforme (mesmo formato em watch/call/meeting): o
            // consumidor prefixa "↓ [#slot net] · ". dDec (frames num 1s) É fps.
            // stats detalhados (como antes): Mbps · fps · buffer de áudio (frames/ms)
            // · buffer de vídeo (frames/ms) · alvo do buffer base/adaptativo (ms) ·
            // e2e (captura→render) · underruns.
            val aMs = (aSpan / 1000).toInt(); val vMs = (vSpan / 1000).toInt()
            val baseTgtMs = (baseTargetUs / 1000).toInt(); val effTgtMs = (effTargetUs / 1000).toInt()
            // e2e RELATIVO (acima do melhor caso): o desvio constante entre os
            // relógios das duas máquinas cancela-se na subtração. O '*' marca
            // relógios desalinhados — fisicamente o e2e nunca pode ser menor que
            // o buffer de vídeo, por isso se o for, o valor bruto não é de fiar.
            val e2eTxt = if (e2eMs < 0 || e2eMinMs == Int.MAX_VALUE) "—" else {
                val rel = (e2eMs - e2eMinMs).coerceAtLeast(0)
                "+${rel}ms" + if (e2eMs < vMs) "*" else ""
            }
            onStats(
                "${"%.2f".format(rxMbps)}Mbps · ${dDec}fps · " +
                "aud ${aN}f/${aMs}ms · vid ${vN}f/${vMs}ms · " +
                "tgt ${baseTgtMs}/${effTgtMs}ms · e2e $e2eTxt · ${stUnder}d"
            )
        }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        bridge.unsubscribe(kindVideo)
        bridge.unsubscribe(kindAudio)
        vThread?.join(2000); aThread?.join(2000); statsThread?.join(1500)
        track = null
    }
}
