package com.livepoc.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), StreamrBridge.Listener {

    private lateinit var bridge: StreamrBridge
    private var broadcaster: Broadcaster? = null
    private var viewer: Viewer? = null
    // chamada 1:1: emissão (cv/ca) + receção do par (rv/ra) em simultâneo
    private var callBroadcaster: Broadcaster? = null
    private var callViewer: Viewer? = null
    private var callMicMuted = false
    private var callTorchOn = false
    private var callHasTorch = false
    private var meetTorchOn = false
    private var meetHasTorch = false
    private var pendingCallRole: String? = null
    // config unificada (mesma página/estilo para LIVE, Call e Meeting)
    private var cfgMode = "live"                 // "live" | "call" | "meet"
    private var pendingCfgRole: String? = null   // após pedir permissões
    // valores capturados da página de config (usados pelos arranques de sessão)
    private var cfgW = 1280; private var cfgH = 720
    private var cfgBr = 2_000_000; private var cfgKfMs = 1000; private var cfgAudioBr = 64_000
    private var cfgCamId: String? = null; private var cfgScreen = false; private var cfgNoCam = false
    private var cfgFps = 30; private var cfgAdapt = true
    // qualidade da chamada (persistida): (largura, altura, bitrate vídeo)
    private val CALL_Q = listOf(
        "480p · 0.8 Mbps" to Triple(640, 480, 800_000),
        "480p · 1.2 Mbps" to Triple(640, 480, 1_200_000),
        "720p · 2.0 Mbps" to Triple(1280, 720, 2_000_000))
    private val CALL_A = listOf("32 kbps" to 32_000, "64 kbps" to 64_000)
    // meeting many-to-many: single-partition por slot (#0-#8), presença na #9
    private var meeting: MeetingEngine? = null
    private var meetBroadcaster: Broadcaster? = null
    private var meetMicMuted = false
    private var meetCamId: String? = null
    private var meetW = 640; private var meetH = 480
    private var pendingMeet = false
    private var pendingMeetSrc = 0
    private var bridgeAddr: String? = null // id de presença (endereço Streamr)
    // tile de um slot remoto: raiz + AspectFrameLayout (aspect fiel) + placeholder
    // + surface (para devolver o vídeo ao sair do fullscreen) + último aspecto
    private class MeetTile(val root: View, val aspect: AspectFrameLayout, val ph: View, val sv: SurfaceView) {
        var aw = 4; var ah = 3
        var fill = false // enquadramento ESCOLHIDO PELO PEER (hb fill)
    }
    private val meetTiles = HashMap<Int, MeetTile>()
    // DESTAQUE: toque num tile → 2×2 na grelha; 2º toque → fullscreen; toque no
    // fullscreen → volta. -1 = tile local; null = sem destaque.
    private var meetSpotSlot: Int? = null
    private var meetSpotFs = false
    private var meetFill = false // enquadramento global: FIT (letterbox) ↔ FILL (corta)
    private var meetBrBase = 500_000        // bitrate escolhido (teto do adaptativo)
    private val MEET_DL_BUDGET = 1_800_000  // orçamento download/participante (medido: 3-way estável ~1.3-1.8M)
    private var meetIsScreen = false
    private var meetCamOff = false
    private var meetSensor = 270
    private var meetPendingProj: android.media.projection.MediaProjection? = null
    private val MEET_SRC = listOf("Camera", "Screen", "No camera (audio/spectator)")
    private val MEET_Q = listOf(
        "240p · 0.3 Mbps" to Triple(320, 240, 300_000),
        "480p · 0.5 Mbps" to Triple(640, 480, 500_000),
        "480p · 0.8 Mbps" to Triple(640, 480, 800_000),
        "720p · 1.5 Mbps" to Triple(1280, 720, 1_500_000),
        "720p · 2.0 Mbps" to Triple(1280, 720, 2_000_000),
        "1080p · 3.0 Mbps" to Triple(1920, 1080, 3_000_000))
    // swap preview↔remoto + PiP móvel/redimensionável + zoom no preview fullscreen
    private var callSwapped = false
    private var callFill = false     // FIT/FILL do vídeo principal (viewing local)
    private var callCamOff = false   // câmara própria cortada na chamada
    private var callPeerCamOff = false // câmara do par cortada (via flag de controlo)
    private var callRemoteW = 3; private var callRemoteH = 4
    private var callW = 640; private var callH = 480
    private var callSensor = 270 // frontal típica
    private var callCamId: String? = null
    private var callZoom = 1f; private var callMinZoom = 1f; private var callMaxZoom = 1f
    private var pipScaleIdx = 0
    private val PIP_SCALES = floatArrayOf(1f, 1.4f, 1.85f)

    private lateinit var pages: Map<String, View>
    private var currentPage = "home"
    private var bcastW = 1280
    private var bcastH = 720
    private var bcastSensor = 90
    private var lastNetworkId: String? = null
    private var lastReconnectMs = 0L
    private var bcastCamId: String? = null // câmara atual do live (flip)
    // Rotação da partilha de ecrã: um app EM BACKGROUND tem a rotação congelada
    // na sua última orientação (o DisplayManager reporta-lhe portrait mesmo com o
    // ecrã em landscape). O sinal fiável é a orientação FÍSICA do sensor, que
    // funciona em background — mapeada a portrait/landscape e só quando o
    // auto-rotate está ON (senão o ecrã não roda de facto).
    private var lastScreenLandscape: Boolean? = null
    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val auto = try {
                    android.provider.Settings.System.getInt(contentResolver,
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                } catch (e: Exception) { true }
                if (!auto) return
                onScreenOrientation((deg in 45..134) || (deg in 225..314))
            }
        }
    }
    private fun onScreenOrientation(landscape: Boolean) {
        android.util.Log.d("ScreenCap", "sensor landscape=$landscape (prev=$lastScreenLandscape page=$currentPage screen=$isScreenShare/$callIsScreen)")
        if (landscape == lastScreenLandscape) return
        if (currentPage == "broadcast" && isScreenShare) {
            lastScreenLandscape = landscape
            val (w, h) = screenCaptureDimsFor(landscape, minOf(bcastW, bcastH))
            if (w != bcastW || h != bcastH) {
                bcastW = w; bcastH = h; broadcaster?.resizeScreenCapture(w, h)
                runOnUiThread { applyPreviewAspect() }
            }
        } else if (currentPage == "call" && callIsScreen) {
            lastScreenLandscape = landscape
            val (w, h) = screenCaptureDimsFor(landscape, minOf(callW, callH))
            if (w != callW || h != callH) {
                callW = w; callH = h; callBroadcaster?.resizeScreenCapture(w, h)
                runOnUiThread { routeCallSurfaces() }
            }
        } else if (currentPage == "meet" && meetIsScreen) {
            lastScreenLandscape = landscape
            val (w, h) = screenCaptureDimsFor(landscape, minOf(meetW, meetH))
            if (w != meetW || h != meetH) {
                meetW = w; meetH = h; meetBroadcaster?.resizeScreenCapture(w, h)
                runOnUiThread { applyMeetLocalAspect() }
            }
        }
    }
    private fun enableScreenOrientation(on: Boolean) {
        lastScreenLandscape = null
        if (on && orientationListener.canDetectOrientation()) orientationListener.enable()
        else orientationListener.disable()
    }
    private val DEFAULT_STREAM_ID = "0x75fc31876b8cd9af59a0e882d87dd8468c2d0e35/video"
    private var lastWatchPass = ""      // prefill do diálogo Watch (só memória)
    private var callPass: String? = null // password da chamada (do dialog_call)
    private val RES = listOf("480p (640x480)" to Pair(640, 480), "720p (1280x720)" to Pair(1280, 720), "1080p (1920x1080)" to Pair(1920, 1080))
    private val SCREEN_FPS = listOf(30, 60) // fps escolhível para partilha de ecrã
    private val ABR = listOf("32 kbps" to 32_000, "64 kbps" to 64_000, "128 kbps" to 128_000)
    private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val REQ_PERMS = 1
    private val REQ_NOTIF = 2
    private val REQ_CALL = 3
    private val REQ_SCREEN = 5
    private val REQ_SCREEN_SWITCH = 6 // trocar para ecrã em pleno broadcast
    private val REQ_SCREEN_CALL = 7   // trocar para ecrã em plena chamada
    private val REQ_MEET = 8
    private val REQ_SCREEN_MEET = 9        // entrar no meeting com fonte = ecrã
    private val REQ_SCREEN_MEET_SWITCH = 10 // trocar para ecrã em pleno meeting
    private val REQ_SCREEN_CALL_START = 11 // entrar na chamada com fonte = ecrã
    private var camOpts: List<Pair<String, String>> = emptyList() // camera id → label
    private var netLine = ""      // overlay info (proxies/mesh) appended to the consoles
    private var lastBStats = ""
    private var lastWStats = ""
    private var lastCStatsUp = ""   // call: a NOSSA emissão (↑)
    private var lastCStatsDn = ""   // call: o feed do par (↓)
    private var lastMeetStats = ""

    // medidor UNIFORME da barra de topo (todos os modos): resolução + seta ↑/↓.
    // Toque no chip do medidor alterna a direção; "—" quando essa direção não
    // tem tráfego (ex.: proxy só sobe, ou watch só desce). meterRes = "720p".
    private var meterUp = true
    private var meterTxMbps = -1.0
    private var meterRxMbps = -1.0
    private var meterRes = ""
    private var meterBr = -1.0          // bitrate da sessão (Mbps)
    private var meterFps = -1           // fps (encode no emissor, decode no viewer)
    private var meterMetric = 0         // zona esquerda: 0=resolução 1=bitrate 2=fps
    // definições EM VIGOR na sessão atual (a engrenagem do overlay altera-as ao vivo)
    private var sessionBr = 2_000_000
    private var sessionFps = 30
    private var sessionAbr = 64_000

    // session UI: timer, auto-hide, fullscreen, mute/torch/zoom
    private val ui = Handler(Looper.getMainLooper())
    private var sessionStartMs = 0L
    private var micMuted = false
    private var torchOn = false
    private var volMuted = false
    private var viewerPaused = false
    private var fullscreen = false
    private var controlsVisible = true
    private var curZoom = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var liveNow = false
    private val hideControls = Runnable {
        when (currentPage) {
            "watch" -> setWatchControlsVisible(false)
            "call" -> setCallControlsVisible(false)
            "meet" -> setMeetControlsVisible(false)
        }
    }
    private val timerTick = object : Runnable {
        override fun run() {
            val secs = (android.os.SystemClock.elapsedRealtime() - sessionStartMs) / 1000
            val txt = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
                      else "%02d:%02d".format(secs / 60, secs % 60)
            if (currentPage == "broadcast") findViewById<TextView>(R.id.bTimer).text = txt
            if (currentPage == "watch") findViewById<TextView>(R.id.wTimer).text = txt
            if (currentPage == "call") findViewById<TextView>(R.id.cTimer).text = txt
            if (currentPage == "meet") findViewById<TextView>(R.id.meetTimer).text = txt
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // targetSdk 35 = edge-to-edge: without this the app bars sit under the
        // system status bar / gesture area. In fullscreen the bars hide →
        // insets collapse to 0 → the video gets the whole screen.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.pageBroadcast).setPadding(bars.left, bars.top, bars.right, bars.bottom)
            findViewById<View>(R.id.pageWatch).setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // call/meet: sem esta margem, a barra de topo (chip que abre a consola)
            // ficava POR BAIXO da status bar do sistema → o toque não a apanhava
            findViewById<View>(R.id.pageCall).setPadding(bars.left, bars.top, bars.right, bars.bottom)
            findViewById<View>(R.id.pageMeet).setPadding(bars.left, bars.top, bars.right, bars.bottom)
            findViewById<View>(R.id.pageHome).setPadding(0, bars.top, 0, bars.bottom)
            findViewById<View>(R.id.pageSettings).setPadding(0, bars.top, 0, bars.bottom)
            findViewById<View>(R.id.pageNetwork).setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        pages = mapOf(
            "home" to findViewById(R.id.pageHome),
            "network" to findViewById(R.id.pageNetwork),
            "settings" to findViewById(R.id.pageSettings),
            "broadcast" to findViewById(R.id.pageBroadcast),
            "watch" to findViewById(R.id.pageWatch),
            "call" to findViewById(R.id.pageCall),
            "meet" to findViewById(R.id.pageMeet)
        )

        setupSettings()
        setupProxySelectors()
        setupSessionControls()

        // Persistent surface callbacks (background support): when the activity
        // hides, the SurfaceViews die — detach them so the sessions survive
        // (audio/broadcast keep running); reattach on return.
        findViewById<SurfaceView>(R.id.watchSurface).holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { viewer?.setVideoSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { viewer?.setVideoSurface(null) }
        })
        findViewById<SurfaceView>(R.id.previewSurface).holder.addCallback(object : SurfaceHolder.Callback {
            private var lastW = 0; private var lastH = 0
            override fun surfaceCreated(h: SurfaceHolder) { broadcaster?.setPreviewSurface(h.surface) }
            // mudar a resolução muda o ASPETO da view (480p 4:3 ↔ 720p 16:9): sem
            // refazer a sessão, a câmara escrevia com as dimensões antigas (deformada)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                if (w == lastW && hh == lastH) return
                val first = lastW == 0
                lastW = w; lastH = hh
                if (!first) broadcaster?.refreshPreviewSurface(h.surface)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) { lastW = 0; lastH = 0; broadcaster?.setPreviewSurface(null) }
        })
        // superfícies da chamada: destacáveis (background) e COMUTÁVEIS (swap
        // preview↔remoto) — o conteúdo é re-encaminhado, as views ficam quietas
        // (o PiP é sempre media-overlay, por cima; trocar views trocaria o Z)
        findViewById<SurfaceView>(R.id.callLocalSurface).setZOrderMediaOverlay(true)
        findViewById<SurfaceView>(R.id.callRemoteSurface).holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { routeCallSurfaces() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (callSwapped) callBroadcaster?.setPreviewSurface(null) else callViewer?.setVideoSurface(null)
            }
        })
        findViewById<SurfaceView>(R.id.callLocalSurface).holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { routeCallSurfaces() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (callSwapped) callViewer?.setVideoSurface(null) else callBroadcaster?.setPreviewSurface(null)
            }
        })
        setupCallGestures()

        findViewById<Button>(R.id.goLive).setOnClickListener { openConfig("live") }
        findViewById<Button>(R.id.goWatch).setOnClickListener {
            if (!bridge.connected) { toast("Still connecting to Streamr…"); return@setOnClickListener }
            // password de decifra (streams encriptados) — vazio = sem encriptação;
            // também pode ser corrigida depois: o Viewer decifra ao vivo
            val input = android.widget.EditText(this).apply {
                hint = "Password (empty if none)"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(lastWatchPass)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Watch")
                .setView(input)
                .setPositiveButton("Watch") { _, _ ->
                    lastWatchPass = input.text.toString()
                    startWatch(lastWatchPass.ifEmpty { null })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<Button>(R.id.goCall).setOnClickListener { openConfig("call") }
        findViewById<Button>(R.id.goMeet).setOnClickListener { openConfig("meet") }
        findViewById<Button>(R.id.cfgGuestBtn).setOnClickListener { onConfigStart("guest") }
        findViewById<ImageButton>(R.id.meetMicBtn).setOnClickListener {
            meetMicMuted = !meetMicMuted
            meetBroadcaster?.setMicMuted(meetMicMuted)
            styleToggle(R.id.meetMicBtn, meetMicMuted, if (meetMicMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        findViewById<ImageButton>(R.id.meetTorchBtn).setOnClickListener {
            meetTorchOn = !meetTorchOn
            applyMeetFlash()
        }
        findViewById<ImageButton>(R.id.meetTorchBtn).setOnLongClickListener {
            showFlashMenu(it, meetFlashSrc) { s -> meetFlashSrc = s; applyMeetFlash() }; true
        }
        findViewById<ImageButton>(R.id.meetCamBtn).setOnClickListener { toggleMeetCam() }
        findViewById<ImageButton>(R.id.meetScreenBtn).setOnClickListener {
            if (meeting == null) return@setOnClickListener
            if (meetIsScreen) switchMeetToCamera()
            else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_MEET_SWITCH)
            }
        }
        findViewById<ImageButton>(R.id.meetFlipBtn).setOnClickListener { flipMeetCamera() }
        findViewById<ImageButton>(R.id.meetFlipBtn).setOnLongClickListener {
            if (!meetCamOff && !meetIsScreen) showCameraMenu(it, meetCamId) { id -> meetCamId = id; meetBroadcaster?.switchCamera(id); applyMeetLocalAspect() }; true
        }
        // consola abre ao tocar no chip MEET (o botão <> foi removido)
        findViewById<TextView>(R.id.meetChip).setOnClickListener { toggleConsole() }
        findViewById<Button>(R.id.meetLeaveBtn).setOnClickListener { stopMeeting(); show("home") }
        // destaque: toque no tile local → 2×2 → fullscreen; toque no fs volta
        findViewById<View>(R.id.meetLocalTile).setOnClickListener { meetTileTap(-1) }
        findViewById<View>(R.id.meetFsBox).setOnClickListener { exitMeetSpot() }
        findViewById<SurfaceView>(R.id.meetFsSurface).holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { routeMeetFs() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                // a surface de fs morreu (saída, background, rotação): NUNCA deixar
                // o viewer preso nela — renderizava para uma surface morta com stats
                // saudáveis = "imagem congelada" (visto ao vivo). Solta já; o
                // surfaceCreated seguinte (fs ou tile) volta a ligar.
                val s = meetSpotSlot ?: return
                if (!meetSpotFs) return
                if (s == -1) meetBroadcaster?.setPreviewSurface(null)
                else meeting?.viewers?.get(s)?.setVideoSurface(null)
            }
        })
        // enquadramento FIT↔FILL do MEU vídeo: define como os OUTROS me veem
        // (vai nos heartbeats; cada peer aplica o enquadramento de cada emissor)
        findViewById<TextView>(R.id.meetFitBtn).setOnClickListener {
            meetFill = !meetFill
            (it as TextView).text = if (meetFill) "FILL" else "FIT"
            findViewById<AspectFrameLayout>(R.id.meetLocalAspect).fillMode = meetFill
            if (meetSpotFs && meetSpotSlot == -1)
                findViewById<AspectFrameLayout>(R.id.meetFsAspect).fillMode = meetFill
            meeting?.setFill(meetFill)
        }
        // preview local do meeting: destacável em background (o emissor continua)
        findViewById<SurfaceView>(R.id.meetLocalSurface).holder.addCallback(object : SurfaceHolder.Callback {
            private var lastW = 0; private var lastH = 0
            override fun surfaceCreated(h: SurfaceHolder) {
                if (!(meetSpotFs && meetSpotSlot == -1)) meetBroadcaster?.setPreviewSurface(h.surface)
            }
            // nova resolução → buffer com outro aspeto: refazer a sessão (ver broadcast)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                if (w == lastW && hh == lastH) return
                val first = lastW == 0
                lastW = w; lastH = hh
                if (!first && !(meetSpotFs && meetSpotSlot == -1)) meetBroadcaster?.refreshPreviewSurface(h.surface)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (!(meetSpotFs && meetSpotSlot == -1)) meetBroadcaster?.setPreviewSurface(null)
            }
        })
        findViewById<ImageButton>(R.id.cFlipBtn).setOnClickListener { flipCallCamera() }
        findViewById<ImageButton>(R.id.cFlipBtn).setOnLongClickListener {
            showCameraMenu(it, callCamId) { id -> callCamId = id; callZoom = 1f; callBroadcaster?.switchCamera(id); routeCallSurfaces() }; true
        }
        findViewById<ImageButton>(R.id.cTorchBtn).setOnClickListener {
            callTorchOn = !callTorchOn
            applyCallFlash()
        }
        findViewById<ImageButton>(R.id.cTorchBtn).setOnLongClickListener {
            showFlashMenu(it, callFlashSrc) { s -> callFlashSrc = s; applyCallFlash() }; true
        }
        // paridade com o Meeting: fullscreen, FIT/FILL, cortar câmara
        findViewById<ImageButton>(R.id.cFullscreenBtn).setOnClickListener { setFullscreen(!fullscreen); bumpAutoHide() }
        findViewById<TextView>(R.id.cFitBtn).setOnClickListener {
            callFill = !callFill
            (it as TextView).text = if (callFill) "FILL" else "FIT"
            findViewById<AspectFrameLayout>(R.id.callRemoteBox).fillMode = callFill
            bumpAutoHide()
        }
        findViewById<ImageButton>(R.id.cCamBtn).setOnClickListener { toggleCallCam() }
        findViewById<ImageButton>(R.id.cScreenBtn).setOnClickListener {
            if (callIsScreen) switchCallToCamera()
            else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CALL)
            }
        }
        findViewById<ImageButton>(R.id.bSourceBtn).setOnClickListener {
            if (isScreenShare) switchBroadcastToCamera()
            else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_SWITCH)
            }
        }
        findViewById<ImageButton>(R.id.cMicBtn).setOnClickListener {
            callMicMuted = !callMicMuted
            callBroadcaster?.setMicMuted(callMicMuted)
            styleToggle(R.id.cMicBtn, callMicMuted, if (callMicMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            refreshNotif()
        }
        // consola abre ao tocar no chip CALL (o botão <> foi removido)
        findViewById<TextView>(R.id.cCallChip).setOnClickListener { toggleConsole() }
        findViewById<Button>(R.id.cEndBtn).setOnClickListener { stopCall(); show("home") }
        findViewById<ImageButton>(R.id.gearBtn).setOnClickListener { show("network") }
        findViewById<ImageButton>(R.id.netBack).setOnClickListener { show("home") }
        findViewById<Button>(R.id.settingsBack).setOnClickListener { show("home") }
        findViewById<Button>(R.id.startBtn).setOnClickListener {
            onConfigStart(if (cfgMode == "call") "host" else cfgMode)
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { stopBroadcast(); show("home") }
        findViewById<Button>(R.id.leaveBtn).setOnClickListener { stopWatch(); show("home") }

        // Back: leave the current page (stopping any session) instead of exiting.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentPage) {
                    "network" -> show("home")
                    "settings" -> show("home")
                    "broadcast" -> { stopBroadcast(); show("home") }
                    "watch" -> { stopWatch(); show("home") }
                    "call" -> { stopCall(); show("home") }
                    "meet" -> { stopMeeting(); show("home") }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        // ações da notificação (mute mic/som, pause, end) → chegam pelo serviço
        LiveService.actionHandler = { act -> runOnUiThread { handleNotifAction(act) } }

        bridge = StreamrBridge(this, this,
            prefs().getString("streamId", DEFAULT_STREAM_ID) ?: DEFAULT_STREAM_ID, loadOrCreatePk())
        setupIdentity()

        // Auto-reconnect: switching networks (Wi-Fi↔cellular) kills the Streamr
        // node's connections with no recovery. On change, the bridge is reborn
        // and re-subscription happens in onBridgeConnected.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    val id = network.toString()
                    // Debounce: cellular networks revalidate constantly and each
                    // event fired a full reload (reconnect storm cutting the flow
                    // ~15s each time). Min 20s between reloads; the bridge's
                    // delivery watchdog covers failures inside the window.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastNetworkId != null && lastNetworkId != id && now - lastReconnectMs > 20_000) {
                        lastReconnectMs = now
                        toast("Network changed — reconnecting…")
                        setStatus("network changed · reconnecting…", connectedDot = false)
                        bridge.reconnect()
                    }
                    lastNetworkId = id
                }
            }
        })
    }

    // ============== session page controls ==============
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSessionControls() {
        // tap the video to show/hide controls (auto-hide on the viewer);
        // pinch on the broadcast preview to zoom
        findViewById<View>(R.id.pageWatch).setOnClickListener {
            if (isConsoleOpen()) toggleConsole() else setWatchControlsVisible(!controlsVisible)
        }
        // meeting: toque nos espaços entre tiles = mostrar/esconder overlay (os
        // tiles consomem os seus próprios toques → aí o meetTileTap revela). O
        // ScrollView central cobre o miolo; as faixas topo/fundo caem na página.
        val meetTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isConsoleOpen()) toggleConsole() else setMeetControlsVisible(!controlsVisible)
                return true
            }
        })
        val meetTapTouch = View.OnTouchListener { _, ev -> meetTap.onTouchEvent(ev); false }
        findViewById<View>(R.id.pageMeet).setOnTouchListener(meetTapTouch)
        findViewById<View>(R.id.meetScroll).setOnTouchListener(meetTapTouch)

        val scaleDet = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(curZoom * d.scaleFactor); return true }
        })
        val tapDet = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val vis = findViewById<View>(R.id.bControls).visibility != View.VISIBLE
                val v = if (vis) View.VISIBLE else View.GONE
                findViewById<View>(R.id.bControls).visibility = v
                findViewById<View>(R.id.bTopBar).visibility = v
                findViewById<View>(R.id.stopBtn).visibility = v
                // a consola de stats só reaparece se estava aberta antes
                if (!vis) findViewById<View>(R.id.bStats).visibility = View.GONE
                return true
            }
        })
        findViewById<View>(R.id.pageBroadcast).setOnTouchListener { _, ev ->
            scaleDet.onTouchEvent(ev); tapDet.onTouchEvent(ev); true
        }

        // zoom presets on tap: (0.6×) → 1× → 2× → 4× → back to the widest
        findViewById<TextView>(R.id.zoomChip).setOnClickListener {
            val presets = (listOf(minZoom, 1f, 2f, 4f).distinct())
                .filter { it >= minZoom - 0.01f && it <= maxZoom + 0.01f }.sorted()
            val next = presets.firstOrNull { it > curZoom + 0.05f } ?: presets.first()
            setZoom(next)
        }

        findViewById<ImageButton>(R.id.micBtn).setOnClickListener {
            micMuted = !micMuted
            broadcaster?.setMicMuted(micMuted)
            styleToggle(R.id.micBtn, micMuted, if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            toast(if (micMuted) "Microphone muted" else "Microphone on")
            refreshNotif()
        }
        findViewById<ImageButton>(R.id.torchBtn).setOnClickListener {
            torchOn = !torchOn
            applyBcastFlash()
        }
        findViewById<ImageButton>(R.id.torchBtn).setOnLongClickListener {
            showFlashMenu(it, bcastFlashSrc) { s -> bcastFlashSrc = s; applyBcastFlash() }; true
        }
        // troca de câmara: função partilhada (usada pelo toque curto front↔back e
        // pelas escolhas do menu de toque longo). Repõe torch/zoom (não sobrevivem).
        val bSwitchCam = { id: String ->
            bcastCamId = id
            torchOn = false
            exitScreenFlash()
            styleToggle(R.id.torchBtn, false, R.drawable.ic_flash)
            curZoom = 1f
            findViewById<TextView>(R.id.zoomChip).text = "1.0×"
            broadcaster?.switchCamera(id)
            applyBroadcastMirror()
        }
        findViewById<ImageButton>(R.id.bFlipBtn).setOnClickListener {
            val curFront = camOpts.firstOrNull { it.first == bcastCamId }?.second?.startsWith("Front") == true
            val next = camOpts.firstOrNull { it.first.isNotEmpty() && it.second.startsWith("Front") != curFront }
                ?: return@setOnClickListener
            bSwitchCam(next.first)
        }
        findViewById<ImageButton>(R.id.bFlipBtn).setOnLongClickListener {
            showCameraMenu(it, bcastCamId) { id -> bSwitchCam(id) }; true
        }
        // consola abre ao tocar no chip LIVE (o botão <> foi removido)
        findViewById<TextView>(R.id.bLiveChip).setOnClickListener { toggleConsole() }
        findViewById<TextView>(R.id.wLiveChip).setOnClickListener { toggleConsole() }

        // Medidor da barra de topo com DUAS zonas de toque:
        //  · metade ESQUERDA  → cicla a métrica: resolução → bitrate → fps
        //  · metade DIREITA   → alterna ↑ (upload) ↔ ↓ (download)
        val meterTap = View.OnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                if (ev.x < v.width / 2f) meterMetric = (meterMetric + 1) % 3 else meterUp = !meterUp
                refreshMeter(); bumpAutoHide(); v.performClick()
            }
            true
        }
        listOf(R.id.bMeter, R.id.wMeter, R.id.cMeter, R.id.meetMeter).forEach {
            findViewById<TextView>(it).setOnTouchListener(meterTap)
        }

        // engrenagem do overlay → definições EM SESSÃO (não existe no watcher)
        listOf(R.id.bCogBtn, R.id.cCogBtn, R.id.meetCogBtn).forEach {
            findViewById<ImageButton>(it).setOnClickListener { showSessionSettings() }
        }

        findViewById<ImageButton>(R.id.fsBtn).setOnClickListener { setFullscreen(!fullscreen); bumpAutoHide() }
        // fullscreen (esconde a status bar) em Broadcast e Meeting
        findViewById<ImageButton>(R.id.bFullscreenBtn).setOnClickListener { setFullscreen(!fullscreen) }
        findViewById<ImageButton>(R.id.meetFullscreenBtn).setOnClickListener { setFullscreen(!fullscreen); bumpAutoHide() }

        // volume VERTICAL (altura consistente em portrait/landscape)
        val vol = findViewById<VerticalSeekBar>(R.id.volumeBarV)
        vol.progress = prefs().getInt("volume", 100)
        vol.onUserChanged = { volMuted = false; applyVolume(); bumpAutoHide() }
        vol.onUserDone = { p -> prefs().edit().putInt("volume", p).apply() }
        // toque no ícone de som = mostra/esconde o slider vertical;
        // toque LONGO = mute (sem conflito entre as duas ações)
        findViewById<ImageButton>(R.id.muteBtn).setOnClickListener {
            toggle(R.id.volPanel)
            bumpAutoHide()
        }
        findViewById<ImageButton>(R.id.muteBtn).setOnLongClickListener {
            volMuted = !volMuted
            applyVolume()
            bumpAutoHide()
            refreshNotif()
            true
        }
        findViewById<ImageButton>(R.id.ppBtn).setOnClickListener {
            viewerPaused = !viewerPaused
            viewer?.setPaused(viewerPaused)
            findViewById<ImageButton>(R.id.ppBtn).setImageResource(
                if (viewerPaused) R.drawable.ic_play else R.drawable.ic_pause)
            // pausado: controlos ficam visíveis; a retomar volta o auto-esconder
            if (viewerPaused) ui.removeCallbacks(hideControls) else bumpAutoHide()
            refreshNotif()
        }
    }

    private fun setZoom(ratio: Float) {
        curZoom = ratio.coerceIn(minZoom, maxZoom)
        broadcaster?.setZoom(curZoom)
        findViewById<TextView>(R.id.zoomChip).text = "%.1f×".format(curZoom)
    }

    private fun applyVolume() {
        val p = if (volMuted) 0 else findViewById<VerticalSeekBar>(R.id.volumeBarV).progress
        viewer?.setVolume(p / 100f)
        findViewById<ImageButton>(R.id.muteBtn).setImageResource(
            if (volMuted || p == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun styleToggle(id: Int, active: Boolean, icon: Int) {
        findViewById<ImageButton>(id).apply {
            setImageResource(icon)
            setBackgroundResource(if (active) R.drawable.bg_roundbtn_active else R.drawable.bg_roundbtn)
        }
    }

    private fun toggle(id: Int) {
        val v = findViewById<View>(id)
        v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** Consola de stats do modo atual. */
    private fun consoleId(): Int = when (currentPage) {
        "broadcast" -> R.id.bStats
        "watch" -> R.id.wStats
        "call" -> R.id.cStats
        "meet" -> R.id.meetStats
        else -> 0
    }

    private fun isConsoleOpen(): Boolean {
        val id = consoleId(); if (id == 0) return false
        return findViewById<View>(id).visibility == View.VISIBLE
    }

    /** Abre/fecha a consola do modo atual. ABERTA: cancela o auto-hide — o overlay
     *  fica fixo enquanto se leem os stats. FECHADA: reinicia o timeout para o
     *  overlay voltar a esconder-se. */
    private fun toggleConsole() {
        val id = consoleId(); if (id == 0) return
        val v = findViewById<View>(id)
        val open = v.visibility != View.VISIBLE
        v.visibility = if (open) View.VISIBLE else View.GONE
        if (open) ui.removeCallbacks(hideControls) else bumpAutoHide()
    }

    private fun setWatchControlsVisible(vis: Boolean) {
        controlsVisible = vis
        val v = if (vis) View.VISIBLE else View.GONE
        findViewById<View>(R.id.wControls).visibility = v
        findViewById<View>(R.id.wTopBar).visibility = v
        findViewById<View>(R.id.leaveBtn).visibility = v
        // o slider de volume só aparece por ação explícita no ícone de som;
        // ao esconder os controlos, esconde-se também
        if (!vis) findViewById<View>(R.id.volPanel).visibility = View.GONE
        if (vis) bumpAutoHide() else ui.removeCallbacks(hideControls)
    }

    private fun setCallControlsVisible(vis: Boolean) {
        controlsVisible = vis
        val v = if (vis) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cControls).visibility = v
        findViewById<View>(R.id.cTopBar).visibility = v
        findViewById<View>(R.id.cEndBtn).visibility = v
        if (!vis) findViewById<View>(R.id.cStats).visibility = View.GONE
        if (vis) bumpAutoHide() else ui.removeCallbacks(hideControls)
    }

    private fun setMeetControlsVisible(vis: Boolean) {
        controlsVisible = vis
        val v = if (vis) View.VISIBLE else View.GONE
        findViewById<View>(R.id.meetControls).visibility = v
        findViewById<View>(R.id.meetTopBar).visibility = v
        findViewById<View>(R.id.meetLeaveBtn).visibility = v
        if (!vis) findViewById<View>(R.id.meetStats).visibility = View.GONE
        if (vis) bumpAutoHide() else ui.removeCallbacks(hideControls)
    }

    // ---- medidor uniforme (barra de topo, todos os modos) ----
    private fun meterView(): TextView? = when (currentPage) {
        "broadcast" -> findViewById(R.id.bMeter)
        "watch" -> findViewById(R.id.wMeter)
        "call" -> findViewById(R.id.cMeter)
        "meet" -> findViewById(R.id.meetMeter)
        else -> null
    }

    /** Etiqueta de resolução pela convenção 480p/720p/1080p: usa sempre o LADO
     *  CURTO. Sem isto, um emissor em RETRATO (720x1080 → h=1280) aparecia como
     *  "1280p" enquanto o mesmo stream em paisagem dava "720p". */
    private fun resLabel(w: Int, h: Int) = "${minOf(w, h)}p"

    /** Reinicia o medidor ao entrar num modo: direção primária + resolução. */
    private fun resetMeter(up: Boolean, res: String = "") {
        meterUp = up; meterTxMbps = -1.0; meterRxMbps = -1.0; meterRes = res
        meterBr = -1.0; meterFps = -1; meterMetric = 0; refreshMeter()
    }
    private fun setMeterTx(mbps: Double) { meterTxMbps = mbps; refreshMeter() }
    private fun setMeterRx(mbps: Double) { meterRxMbps = mbps; refreshMeter() }
    private fun setMeterRes(res: String) { meterRes = res; refreshMeter() }
    /** bitrate (Mbps) e fps da sessão — métricas alternativas da zona esquerda. */
    private fun setMeterBr(mbps: Double) { meterBr = mbps; refreshMeter() }
    private fun setMeterFps(fps: Int) { meterFps = fps; refreshMeter() }

    /** Largura ESTÁVEL: a contagem de dígitos mantém-se ao tirar decimais à
     *  medida que o valor cresce (9,99 · 12,3 · 123) — o chip não "salta". */
    private fun fmtMbps(v: Double): String = when {
        v < 0 -> "—"
        v >= 100 -> "%.0f".format(v)
        v >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }

    /** Zona esquerda: a métrica escolhida (resolução / bitrate / fps). */
    private fun meterHead(): String = when (meterMetric) {
        1 -> (if (meterBr >= 0) fmtMbps(meterBr) else "—") + " Mbps"
        2 -> (if (meterFps >= 0) "$meterFps" else "—") + " fps"
        else -> meterRes.ifEmpty { "—" }
    }

    private fun refreshMeter() {
        val tv = meterView() ?: return
        val v = if (meterUp) meterTxMbps else meterRxMbps
        // largura CONSTANTE: o chip é monospace, por isso alinhar a métrica (9 =
        // "2.50 Mbps") e o número (4 = "9.99"/"12.3"/" 123") fixa o tamanho.
        tv.text = meterHead().padEnd(9) + " · " + (if (meterUp) "↑ " else "↓ ") +
            fmtMbps(v).padStart(4) + " Mbps"
    }

    /** Mensagem de estado TRANSITÓRIA: aparece e apaga-se sozinha. Um erro pontual
     *  (ex.: rasto de uma troca de resolução) não deve ficar colado no ecrã enquanto
     *  a emissão continua saudável. Estados permanentes usam .text diretamente. */
    private fun transientStatus(viewId: Int, msg: String, ms: Long = 5000) {
        val tv = findViewById<TextView>(viewId) ?: return
        tv.text = msg
        tv.tag = (tv.tag as? Int ?: 0) + 1
        val gen = tv.tag as Int
        ui.postDelayed({ if (tv.tag as? Int == gen && tv.text == msg) tv.text = "" }, ms)
    }

    /** Restart the 4s auto-hide countdown — called on every interaction. */
    private fun bumpAutoHide() {
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 4000)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        // atualiza o ícone do botão de fullscreen do modo atual (watch/broadcast/meet)
        val icon = if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        val id = when (currentPage) {
            "broadcast" -> R.id.bFullscreenBtn
            "call" -> R.id.cFullscreenBtn
            "meet" -> R.id.meetFullscreenBtn
            else -> R.id.fsBtn
        }
        findViewById<ImageButton>(id).setImageResource(icon)
    }

    // ============== settings ==============
    private fun prefs() = getSharedPreferences("livepoc", Context.MODE_PRIVATE)

    // Rota de áudio de VOZ (meeting/call): o mic usa VOICE_COMMUNICATION (AEC);
    // sem MODE_IN_COMMUNICATION + altifalante o Android roteia a reprodução para
    // o AURICULAR e parece "sem som". Restaura o estado ao sair.
    private var prevAudioMode = android.media.AudioManager.MODE_NORMAL
    private var prevSpeakerphone = false
    private var commAudioOn = false
    private fun setCommAudioRoute(on: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            if (on && !commAudioOn) {
                prevAudioMode = am.mode
                @Suppress("DEPRECATION") run { prevSpeakerphone = am.isSpeakerphoneOn }
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val spk = am.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (spk != null) am.setCommunicationDevice(spk)
                } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = true }
                commAudioOn = true
            } else if (!on && commAudioOn) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = prevSpeakerphone }
                am.mode = prevAudioMode
                commAudioOn = false
            }
        } catch (e: Exception) { android.util.Log.w("Audio", "setCommAudioRoute", e) }
    }

    /** Identidade persistente: PK gerada uma vez e guardada — permite testar
     *  permissões NATIVAS do Streamr (grants a um endereço estável). */
    private fun loadOrCreatePk(): String {
        val p = prefs()
        var pk = p.getString("pk", null)
        if (pk == null || !Regex("^0x[0-9a-fA-F]{64}$").matches(pk)) {
            val b = ByteArray(32); java.security.SecureRandom().nextBytes(b)
            pk = "0x" + b.joinToString("") { "%02x".format(it) }
            p.edit().putString("pk", pk).apply()
        }
        return pk
    }

    private fun setupIdentity() {
        val p = prefs()
        val streamEdit = findViewById<android.widget.EditText>(R.id.streamIdEdit)
        val pkEdit = findViewById<android.widget.EditText>(R.id.pkEdit)
        streamEdit.setText(p.getString("streamId", DEFAULT_STREAM_ID))
        pkEdit.setText(p.getString("pk", ""))
        findViewById<Button>(R.id.pkNewBtn).setOnClickListener {
            val b = ByteArray(32); java.security.SecureRandom().nextBytes(b)
            pkEdit.inputType = android.text.InputType.TYPE_CLASS_TEXT // mostrar a chave nova
            pkEdit.setText("0x" + b.joinToString("") { "%02x".format(it) })
        }
        findViewById<Button>(R.id.idApplyBtn).setOnClickListener {
            val s = streamEdit.text.toString().trim().ifEmpty { DEFAULT_STREAM_ID }
            val k = pkEdit.text.toString().trim()
            if (!Regex("^0x[0-9a-fA-F]{64}$").matches(k)) { toast("Invalid private key (0x + 64 hex)"); return@setOnClickListener }
            p.edit().putString("streamId", s).putString("pk", k).apply()
            bridge.streamId = s; bridge.privateKey = k
            findViewById<TextView>(R.id.netAddr).text = "reconnecting…"
            bridge.reconnect() // renasce o mundo JS já com o stream/chave novos
        }
    }

    private fun pushProxyCounts() {
        val p = prefs()
        bridge.setProxyCounts(p.getInt("proxyPub", 1), p.getInt("proxySub", 1))
        // modos de rede: fast start (malha→proxy), proxy-only (malha proibida).
        // single-partition é o ÚNICO modo (áudio+vídeo na #0; a #1 é ignorada).
        bridge.setModes(p.getBoolean("meshStart", false), p.getBoolean("proxyOnly", false))
    }

    private fun setupProxySelectors() {
        val opts = listOf("0 (mesh)", "1", "2", "3")
        val p = prefs()
        for ((id, key) in listOf(R.id.proxyPubSel to "proxyPub", R.id.proxySubSel to "proxySub")) {
            val sel = findViewById<Spinner>(id)
            sel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
            sel.setSelection(p.getInt(key, 1))
            sel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, idL: Long) {
                    if (p.getInt(key, 1) != pos) {
                        p.edit().putInt(key, pos).apply()
                        pushProxyCounts()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        for ((id, key) in listOf(R.id.meshStartSw to "meshStart", R.id.proxyOnlySw to "proxyOnly")) {
            val sw = findViewById<android.widget.Switch>(id)
            sw.isChecked = p.getBoolean(key, false)
            sw.setOnCheckedChangeListener { _, checked ->
                p.edit().putBoolean(key, checked).apply()
                pushProxyCounts()
            }
        }
        // espelho do preview frontal — definição GLOBAL, igual em todos os modos
        // (broadcast/call/meeting leem mirrorOn() ao montar as superfícies)
        val mirrorSw = findViewById<android.widget.Switch>(R.id.mirrorSw)
        mirrorSw.isChecked = p.getBoolean("mirrorFront", false)
        mirrorSw.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean("mirrorFront", c).apply()
            applyBroadcastMirror()
            if (callBroadcaster != null) routeCallSurfaces()
            if (meeting != null) applyMeetLocalMirror()
        }
    }

    /** Espelho do preview local do meeting (cosmético; front + mirror global). */
    private fun applyMeetLocalMirror() {
        findViewById<SurfaceView>(R.id.meetLocalSurface).scaleX =
            if (!meetIsScreen && mirrorOn() && isFrontId(meetCamId)) -1f else 1f
    }

    /** All cameras the device exposes, labeled by facing and zoom factor vs the main back camera. */
    private fun enumCameras(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            data class Cam(val id: String, val facing: Int, val focal: Float)
            val cams = mgr.cameraIdList.mapNotNull { id ->
                try {
                    val ch = mgr.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                    val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                    Cam(id, facing, focal)
                } catch (e: Exception) { null }
            }
            val mainBackFocal = cams.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }?.focal ?: 0f
            for (c in cams) {
                val label = when (c.facing) {
                    CameraCharacteristics.LENS_FACING_BACK ->
                        if (mainBackFocal > 0f && c.focal > 0f && Math.abs(c.focal - mainBackFocal) > 0.01f)
                            "Back %.1f×".format(c.focal / mainBackFocal)
                        else "Back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    else -> "External"
                }
                out.add(c.id to label)
            }
            // duplicated labels (e.g. two "Back") → disambiguate with the id
            val dup = out.groupBy { it.second }.filterValues { it.size > 1 }.keys
            for (i in out.indices) if (out[i].second in dup) out[i] = out[i].first to "${out[i].second} (${out[i].first})"
        } catch (e: Exception) {}
        return if (out.isEmpty()) listOf("" to "Default") else out
    }

    private fun setupSettings() {
        val srcSel = findViewById<Spinner>(R.id.srcSel)
        srcSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Camera", "Screen share"))
        val resSel = findViewById<Spinner>(R.id.resSel)
        resSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, RES.map { it.first })
        resSel.setSelection(1) // 720p

        camOpts = enumCameras()
        val camSel = findViewById<Spinner>(R.id.camSel)
        camSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, camOpts.map { it.second })

        val audioSel = findViewById<Spinner>(R.id.audioSel)
        audioSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ABR.map { it.first })
        audioSel.setSelection(prefs().getInt("audioBr", 1)) // default 64 kbps

        // fps da partilha de ecrã: 30 ou 60 (persistido; aplica ao vivo se possível)
        val fpsSel = findViewById<Spinner>(R.id.fpsSel)
        fpsSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SCREEN_FPS.map { "$it fps" })
        fpsSel.setSelection(SCREEN_FPS.indexOf(prefs().getInt("screenFps", 30)).coerceAtLeast(0))
        fpsSel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val fps = SCREEN_FPS[pos]
                prefs().edit().putInt("screenFps", fps).apply()
                broadcaster?.changeMaxFps(fps)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val brLabel = findViewById<TextView>(R.id.brLabel)
        findViewById<SeekBar>(R.id.bitrate).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                brLabel.text = "Bitrate %.1f Mbps".format(bitrateOf(p) / 1e6)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun bitrateOf(progress: Int) = 500_000 + progress * 100_000       // 0.5–8.0 Mbps
    private val KF_MS = 1000 // keyframe fixo a 1s (sem configuração)

    private fun hasPerms() = PERMS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) show("settings")
            else toast("Access denied. Enable camera/microphone in system settings.")
        }
        if (code == REQ_CALL) {
            val role = pendingCallRole; pendingCallRole = null
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED } && role != null) startCall(role)
            else toast("Access denied. Enable camera/microphone in system settings.")
        }
        if (code == REQ_MEET) {
            val go = pendingMeet; pendingMeet = false
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED } && go) launchMeeting(pendingMeetSrc)
            else toast("Access denied. Enable camera/microphone in system settings.")
        }
    }

    /** Entrada no meeting conforme a fonte do diálogo: câmara, ecrã (via
     *  consentimento de MediaProjection) ou sem câmara (só áudio/espectador). */
    private fun launchMeeting(srcIdx: Int) {
        if (srcIdx == 1) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_MEET)
        } else startMeeting(null, camOff = srcIdx == 2)
    }

    private fun show(page: String) {
        currentPage = page
        pages.forEach { (k, v) -> v.visibility = if (k == page) View.VISIBLE else View.GONE }
        ui.removeCallbacks(timerTick)
        ui.removeCallbacks(hideControls)
        if (page == "broadcast" || page == "watch" || page == "call" || page == "meet") {
            sessionStartMs = android.os.SystemClock.elapsedRealtime()
            ui.post(timerTick)
        } else if (fullscreen) setFullscreen(false)
    }

    // ================= BROADCAST =================
    private var isScreenShare = false
    private var bcastCamW = 1280
    private var bcastCamH = 720

    /** Dimensões de captura de ecrã: lado curto = resolução escolhida, o
     *  comprido segue a proporção REAL do display NA ORIENTAÇÃO ATUAL.
     *  Usa um DISPLAY CONTEXT (o mais fiável com a app em background durante a
     *  partilha) — a janela da activity e até o DisplayManager direto podem ficar
     *  obsoletos; o WindowManager de um context do display dá os bounds atuais. */
    private fun screenCaptureDims(shortSide: Int): Pair<Int, Int> {
        // no ARRANQUE a app está foreground → a config do display é fiável
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return screenCaptureDimsFor(land, shortSide)
    }

    /** dims com orientação EXPLÍCITA (do sensor) — as magnitudes vêm do tamanho
     *  NATURAL do painel (rotation-invariante), por isso são sempre fiáveis. */
    private fun screenCaptureDimsFor(landscape: Boolean, shortSide: Int): Pair<Int, Int> {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val disp = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION") disp.getRealSize(pt)
        val long = maxOf(pt.x, pt.y); val short = minOf(pt.x, pt.y)
        return if (landscape)
            Pair((shortSide.toLong() * long / short).toInt() / 2 * 2, shortSide)
        else
            Pair(shortSide, (shortSide.toLong() * long / short).toInt() / 2 * 2)
    }

    private fun mirrorOn() = prefs().getBoolean("mirrorFront", false)
    private fun isFrontId(id: String?) =
        camOpts.firstOrNull { it.first == id }?.second?.startsWith("Front") == true

    /** Espelho COSMÉTICO do preview local da frontal (a emissão não muda). */
    private fun applyBroadcastMirror() {
        findViewById<SurfaceView>(R.id.previewSurface).scaleX =
            if (!isScreenShare && mirrorOn() && isFrontId(bcastCamId)) -1f else 1f
    }

    /** Durante a partilha de ecrã o preview da câmara não existe (recursivo) e
     *  a SurfaceView mostrava a última frame congelada — trocar por placeholder. */
    private fun applyScreenPlaceholder() {
        findViewById<View>(R.id.screenPlaceholder).visibility = if (isScreenShare) View.VISIBLE else View.GONE
        findViewById<View>(R.id.previewBox).visibility = if (isScreenShare) View.GONE else View.VISIBLE
    }

    /** Abre a MESMA página de config (estilo LIVE) preparada para o modo pedido.
     *  Fonte/resolução/câmara/bitrate/keyframe/áudio/mirror/fps/password iguais em
     *  todos; extras por modo: adaptativo (meeting) e botão Guest (call). */
    private fun openConfig(mode: String) {
        if (!bridge.connected) { toast("Still connecting to Streamr…"); return }
        if (mode == "live" && liveNow) { toast("A live broadcast is already on air — join as a viewer."); return }
        cfgMode = mode
        val p = prefs()
        findViewById<TextView>(R.id.cfgTitle).text =
            when (mode) { "call" -> "Call · 1:1"; "meet" -> "Meeting"; else -> "New broadcast" }
        val srcOpts = if (mode == "meet") listOf("Camera", "Screen share", "No camera (audio only)")
                      else listOf("Camera", "Screen share")
        findViewById<Spinner>(R.id.srcSel).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, srcOpts)
            setSelection(p.getInt("${mode}Src", 0).coerceIn(0, srcOpts.size - 1))
        }
        findViewById<Spinner>(R.id.resSel).setSelection(p.getInt("${mode}Res", if (mode == "live") 1 else 0))
        val camSel = findViewById<Spinner>(R.id.camSel)
        val defCam = if (mode == "live") 0 else camOpts.indexOfFirst { it.second.startsWith("Front") }.coerceAtLeast(0)
        camSel.setSelection(p.getInt("${mode}Cam", defCam).coerceIn(0, (camSel.adapter?.count ?: 1) - 1))
        findViewById<SeekBar>(R.id.bitrate).progress =
            p.getInt("${mode}Br", if (mode == "live") 15 else if (mode == "meet") 3 else 7)
        findViewById<Spinner>(R.id.audioSel).setSelection(p.getInt("${mode}Audio", if (mode == "live") 1 else 0))
        findViewById<View>(R.id.adaptCb).visibility = if (mode == "meet") View.VISIBLE else View.GONE
        findViewById<android.widget.CheckBox>(R.id.adaptCb).isChecked = p.getBoolean("meetAdapt", true)
        findViewById<Button>(R.id.startBtn).text =
            when (mode) { "call" -> "Start (Host)"; "meet" -> "Join"; else -> "Go Live" }
        findViewById<View>(R.id.cfgGuestBtn).visibility = if (mode == "call") View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.settingsStatus).text = ""
        if (hasPerms()) show("settings")
        else ActivityCompat.requestPermissions(this, PERMS, REQ_PERMS)
    }

    /** Lê a página de config para os campos cfg* e persiste por modo. */
    private fun captureCfg() {
        val p = prefs(); val m = cfgMode
        val srcPos = findViewById<Spinner>(R.id.srcSel).selectedItemPosition
        cfgScreen = srcPos == 1; cfgNoCam = srcPos == 2
        val resPos = findViewById<Spinner>(R.id.resSel).selectedItemPosition
        cfgW = RES[resPos].second.first; cfgH = RES[resPos].second.second
        val brProg = findViewById<SeekBar>(R.id.bitrate).progress
        cfgBr = bitrateOf(brProg); cfgKfMs = KF_MS
        val audioPos = findViewById<Spinner>(R.id.audioSel).selectedItemPosition
        cfgAudioBr = ABR[audioPos].second
        val camPos = findViewById<Spinner>(R.id.camSel).selectedItemPosition
        if (camOpts.isEmpty()) camOpts = enumCameras()
        cfgCamId = if (cfgScreen || cfgNoCam) null else camOpts.getOrNull(camPos)?.first
        cfgFps = SCREEN_FPS[findViewById<Spinner>(R.id.fpsSel).selectedItemPosition]
        cfgAdapt = findViewById<android.widget.CheckBox>(R.id.adaptCb).isChecked
        // ponto de partida das definições EM SESSÃO (a engrenagem altera daqui)
        sessionBr = cfgBr; sessionFps = cfgFps; sessionAbr = cfgAudioBr
        p.edit().putInt("${m}Src", srcPos).putInt("${m}Res", resPos).putInt("${m}Cam", camPos)
            .putInt("${m}Br", brProg).putInt("${m}Audio", audioPos)
            .putBoolean("meetAdapt", cfgAdapt).apply()
    }

    /** Ação do botão primário/guest da config — arranca a sessão do modo atual. */
    private fun onConfigStart(role: String) {
        captureCfg()
        callPass = findViewById<android.widget.EditText>(R.id.bPassEdit).text.toString().ifEmpty { null }
        when (cfgMode) {
            "live" -> startBroadcast() // lê os mesmos controlos + trata o ecrã internamente
            "call" -> if (cfgScreen) { pendingCfgRole = role; requestScreen(REQ_SCREEN_CALL_START) } else startCall(role)
            "meet" -> when {
                cfgScreen -> requestScreen(REQ_SCREEN_MEET)
                cfgNoCam -> startMeeting(null, camOff = true)
                else -> startMeeting(null, camOff = false)
            }
        }
    }

    private fun requestScreen(reqCode: Int) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCode)
    }

    private fun startBroadcast() {
        if (findViewById<Spinner>(R.id.srcSel).selectedItemPosition == 1) {
            // PARTILHA DE ECRÃ: 1º o consentimento do sistema; o arranque real
            // segue em onActivityResult (FGS mediaProjection antes do projection)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN)
            return
        }
        beginBroadcastSession(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in listOf(REQ_SCREEN, REQ_SCREEN_SWITCH, REQ_SCREEN_CALL, REQ_SCREEN_CALL_START, REQ_SCREEN_MEET, REQ_SCREEN_MEET_SWITCH)) return
        if (resultCode != RESULT_OK || data == null) { toast("Screen share cancelled."); return }
        // Android 14+: o FGS de tipo mediaProjection TEM de estar ativo ANTES
        // de obter o MediaProjection — (re)arranca o serviço e espera-o assentar
        ensureNotifPerm()
        val svcMode = if (requestCode in listOf(REQ_SCREEN_CALL, REQ_SCREEN_CALL_START, REQ_SCREEN_MEET, REQ_SCREEN_MEET_SWITCH)) "call-screen" else "screen"
        if (requestCode == REQ_SCREEN) bridge.monitorStop()
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", svcMode))
        ui.postDelayed({
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val mp = mpm.getMediaProjection(resultCode, data)
                when (requestCode) {
                    REQ_SCREEN -> beginBroadcastSession(mp)
                    REQ_SCREEN_SWITCH -> switchBroadcastToScreen(mp)
                    REQ_SCREEN_CALL -> switchCallToScreen(mp)
                    REQ_SCREEN_CALL_START -> startCall(pendingCfgRole ?: "host", mp)
                    REQ_SCREEN_MEET -> startMeeting(mp, camOff = false)
                    REQ_SCREEN_MEET_SWITCH -> switchMeetToScreen(mp)
                }
            } catch (e: Exception) {
                toast("Screen capture failed: ${e.message}")
                if (requestCode == REQ_SCREEN) {
                    stopService(Intent(this, LiveService::class.java))
                    bridge.monitorStart()
                }
            }
        }, 600)
    }

    /** Broadcast: câmara → ecrã em pleno live (o encoder renasce nas dims novas;
     *  os viewers seguem pela mudança de coded size no keyframe). */
    private fun switchBroadcastToScreen(mp: android.media.projection.MediaProjection) {
        val (w, h) = screenCaptureDims(minOf(bcastCamW, bcastCamH))
        isScreenShare = true
        bcastW = w; bcastH = h; bcastSensor = 0
        broadcaster?.setVideoSource(null, mp, w, h)
        findViewById<View>(R.id.bFlipBtn).visibility = View.GONE
        findViewById<View>(R.id.torchBtn).visibility = View.GONE
        findViewById<View>(R.id.zoomChip).visibility = View.GONE
        findViewById<TextView>(R.id.bStatus).text = "sharing screen"
        applyPreviewAspect()
        applyBroadcastMirror()
        applyScreenPlaceholder()
        enableScreenOrientation(true)
    }

    /** Broadcast: ecrã → câmara (dims de câmara originais; FGS volta a camera|mic). */
    private fun switchBroadcastToCamera() {
        isScreenShare = false
        enableScreenOrientation(false)
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "broadcast"))
        val camId = bcastCamId ?: camOpts.firstOrNull { it.second.startsWith("Back") }?.first
        bcastCamId = camId
        bcastW = bcastCamW; bcastH = bcastCamH
        broadcaster?.setVideoSource(camId, null, bcastCamW, bcastCamH)
        findViewById<View>(R.id.bFlipBtn).visibility = View.VISIBLE
        findViewById<TextView>(R.id.bStatus).text = ""
        applyBroadcastMirror()
        applyScreenPlaceholder()
        // torch/zoom/aspect voltam pelo onCameraInfo quando a câmara abrir
    }

    // ---- chamada: câmara ↔ ecrã ----
    private var callIsScreen = false

    private fun switchCallToScreen(mp: android.media.projection.MediaProjection) {
        callIsScreen = true
        val (w, h) = screenCaptureDims(minOf(callW, callH))
        callW = w; callH = h; callSensor = 0
        callBroadcaster?.setVideoSource(null, mp, w, h)
        findViewById<View>(R.id.cFlipBtn).visibility = View.GONE
        resetCallTorch() // sem lanterna na partilha de ecrã
        routeCallSurfaces() // re-fixa o buffer + aspects + espelho
        enableScreenOrientation(true)
    }

    private fun switchCallToCamera() {
        callIsScreen = false
        enableScreenOrientation(false)
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        callW = cfgW; callH = cfgH
        val camId = callCamId ?: camOpts.firstOrNull { it.second.startsWith("Front") }?.first
        callCamId = camId
        callBroadcaster?.setVideoSource(camId, null, callW, callH)
        findViewById<View>(R.id.cFlipBtn).visibility = View.VISIBLE
        refreshCallTorchBtn() // volta o botão (se a câmara tiver flash)
        routeCallSurfaces()
    }

    private fun beginBroadcastSession(projection: android.media.projection.MediaProjection?) {
        isScreenShare = projection != null
        var (w, h) = RES[findViewById<Spinner>(R.id.resSel).selectedItemPosition].second
        bcastCamW = w; bcastCamH = h // dims de CÂMARA (para voltar do screen share)
        if (isScreenShare) {
            val d = screenCaptureDims(minOf(w, h))
            w = d.first; h = d.second
        }
        val camPos = findViewById<Spinner>(R.id.camSel).selectedItemPosition
        val camId = camOpts.getOrNull(camPos)?.first?.ifEmpty { null }
        bcastCamId = camId ?: camOpts.firstOrNull { it.second.startsWith("Back") }?.first
        val bitrate = bitrateOf(findViewById<SeekBar>(R.id.bitrate).progress)
        val kfMs = KF_MS
        val abrPos = findViewById<Spinner>(R.id.audioSel).selectedItemPosition
        val audioBitrate = ABR.getOrNull(abrPos)?.second ?: 64_000
        prefs().edit().putInt("audioBr", abrPos).apply()

        if (!isScreenShare) {
            ensureNotifPerm()
            bridge.monitorStop() // the session takes over the audio subscription
            startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "broadcast"))
        }
        show("broadcast")
        micMuted = false; torchOn = false; curZoom = 1f; minZoom = 1f; maxZoom = 1f
        styleToggle(R.id.micBtn, false, R.drawable.ic_mic)
        styleToggle(R.id.torchBtn, false, R.drawable.ic_flash)
        findViewById<TextView>(R.id.zoomChip).text = "1.0×"
        findViewById<View>(R.id.bControls).visibility = View.VISIBLE
        findViewById<View>(R.id.bTopBar).visibility = View.VISIBLE
        findViewById<View>(R.id.stopBtn).visibility = View.VISIBLE
        findViewById<TextView>(R.id.bStatus).text = if (isScreenShare) "sharing screen" else ""
        // medidor uniforme: emissão sobe (↑); download fica "—"
        resetMeter(up = true, res = resLabel(w, h))
        // sem câmara não há flip; torch/zoom são geridos pelo onCameraInfo
        findViewById<View>(R.id.bFlipBtn).visibility = if (isScreenShare) View.GONE else View.VISIBLE
        bcastW = w; bcastH = h
        applyPreviewAspect()
        applyBroadcastMirror()
        applyScreenPlaceholder()
        enableScreenOrientation(isScreenShare) // segue a rotação física quando ecrã

        val sv = findViewById<SurfaceView>(R.id.previewSurface)
        sv.holder.setFixedSize(w, h)
        val begin = begin@{
            if (broadcaster != null) return@begin
            broadcaster = Broadcaster(
                this, bridge,
                // partilha de ecrã: sem preview (espelhar-se a si próprio é recursivo)
                if (isScreenShare) null else sv.holder.surface,
                w, h, bitrate, kfMs,
                if (isScreenShare) null else camId, audioBitrate,
                singlePartition = true, // single-partition é o único modo
                screenProjection = projection,
                onStats = { s -> runOnUiThread { lastBStats = s; renderConsole() } },
                onError = { e -> runOnUiThread { toast(e); transientStatus(R.id.bStatus, e) } },
                onCameraInfo = { so, hasTorch, minZ, maxZ -> runOnUiThread {
                    bcastSensor = so; applyPreviewAspect()
                    minZoom = minZ; maxZoom = maxZ
                    findViewById<View>(R.id.torchBtn).visibility = if (!isScreenShare) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.zoomChip).visibility =
                        if (maxZ > 1.05f || minZ < 0.95f) View.VISIBLE else View.GONE
                } },
                onMeter = { _, br, fps -> runOnUiThread { setMeterBr(br); setMeterFps(fps) } }
            ).also {
                it.encPassword = findViewById<android.widget.EditText>(R.id.bPassEdit)
                    .text.toString().ifEmpty { null }
                // fps escolhido (partilha de ecrã) — definido antes de start()
                it.maxFps = prefs().getInt("screenFps", 30)
                it.displayRotationDeg = displayRotationDeg(); it.start()
            }
        }
        if (isScreenShare || sv.holder.surface?.isValid == true) begin()
        else sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { begin(); sv.holder.removeCallback(this) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    /**
     * The system rotates the camera buffer to the display orientation on a
     * SurfaceView, so the on-screen image is transposed when the relative
     * rotation (sensor − display) is 90°/270° — the container must follow or
     * the preview gets squashed.
     */
    private fun applyPreviewAspect() {
        if (isScreenShare) {
            // dims de captura de ecrã já vêm na orientação certa — sem transpor
            findViewById<AspectFrameLayout>(R.id.previewBox).setAspect(bcastW, bcastH)
            return
        }
        val rel = (bcastSensor - displayRotationDeg() + 360) % 360
        val t = rel == 90 || rel == 270
        findViewById<AspectFrameLayout>(R.id.previewBox)
            .setAspect(if (t) bcastH else bcastW, if (t) bcastW else bcastH)
    }

    private fun displayRotationDeg(): Int {
        val rot = if (Build.VERSION.SDK_INT >= 30) display?.rotation
        else @Suppress("DEPRECATION") windowManager.defaultDisplay?.rotation
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // foreground: a config do PomboTV rodou → atualiza já a captura de ecrã
        // (o sensor cobre o caso de a app estar em background)
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (currentPage == "broadcast") {
            applyPreviewAspect()
            broadcaster?.displayRotationDeg = displayRotationDeg()
            if (isScreenShare) onScreenOrientation(land)
        }
        if (currentPage == "call") {
            applyCallAspects()
            callBroadcaster?.displayRotationDeg = displayRotationDeg()
            if (callIsScreen) onScreenOrientation(land)
        }
        if (currentPage == "meet") {
            // landscape: os AspectFrameLayout dos tiles mantêm o aspecto; só o
            // preview local depende da rotação do display (sensor transposto),
            // e as células da grelha recalculam para a largura nova
            applyMeetLocalAspect()
            applyMeetSpot()
            meetBroadcaster?.displayRotationDeg = displayRotationDeg()
            if (meetIsScreen) onScreenOrientation(land)
        }
    }

    private fun stopBroadcast() {
        exitScreenFlash()
        enableScreenOrientation(false)
        // Teardown joins encoder/camera threads — off the main thread (blocking
        // it swallows input events and can ANR).
        val b = broadcaster ?: return
        broadcaster = null
        // monitor restart ONLY after the session teardown finished — starting it
        // while the session's leave/unsubscribe is still in flight raced in the
        // SDK and left the monitor subscription dead (no live detection).
        // guarda: se o usuário JÁ entrou noutra sessão quando isto aterra,
        // não arrancar o monitor (corria por cima da subscrição da sessão)
        thread(name = "stop-broadcast") { b.stop(); runOnUiThread { if (viewer == null && broadcaster == null) bridge.monitorStart() } }
        stopService(Intent(this, LiveService::class.java))
        lastBStats = ""; netLine = ""
        findViewById<TextView>(R.id.bStats).text = ""
        findViewById<View>(R.id.bStats).visibility = View.GONE
    }

    // ================= WATCH =================
    private fun startWatch(encPass: String? = null) {
        ensureNotifPerm()
        // sem monitorStop: o bridgeSubscribe('audio') herda a subscrição viva do
        // monitor (handover) — arranque do Watch sem o teardown de ~7s
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "watch"))
        show("watch")
        viewerPaused = false
        findViewById<ImageButton>(R.id.ppBtn).setImageResource(R.drawable.ic_pause)
        findViewById<View>(R.id.volPanel).visibility = View.GONE
        setWatchControlsVisible(true)
        findViewById<TextView>(R.id.wStatus).text = "waiting for signal…"
        // medidor uniforme: Watch desce (↓); resolução chega no onVideoSize
        resetMeter(up = false)
        findViewById<View>(R.id.wSpinner).visibility = View.VISIBLE
        findViewById<AspectFrameLayout>(R.id.watchBox).setAspect(16, 9) // until the real size arrives
        val sv = findViewById<SurfaceView>(R.id.watchSurface)
        val begin = begin@{
            if (viewer != null) return@begin
            viewer = Viewer(
                bridge, sv.holder.surface,
                onState = { s -> runOnUiThread {
                    findViewById<TextView>(R.id.wStatus).text = s
                    // buffering spinner: visible through the waiting/startup phases
                    val buffering = s.contains("waiting") || s.contains("stabiliz") ||
                        s.contains("buffer") || s.contains("reconnect")
                    findViewById<View>(R.id.wSpinner).visibility = if (buffering) View.VISIBLE else View.GONE
                } },
                onStats = { s -> runOnUiThread { lastWStats = "↓ · $s"; renderConsole() } },
                onVideoSize = { w, h -> runOnUiThread {
                    findViewById<AspectFrameLayout>(R.id.watchBox).setAspect(w, h); setMeterRes(resLabel(w, h)) } },
                // watcher puro: fps de decode e bitrate ANUNCIADO pelo emissor
                // (o ↑/↓ do medidor vem do tráfego real da ponte, não daqui)
                onMeter = { _, fps, br -> runOnUiThread { setMeterFps(fps); setMeterBr(br) } }
            ).also { it.encPassword = encPass; it.start(); applyVolume() }
        }
        if (sv.holder.surface?.isValid == true) begin()
        else sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { begin(); sv.holder.removeCallback(this) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun stopWatch() {
        val v = viewer ?: return
        viewer = null
        thread(name = "stop-watch") { v.stop(); runOnUiThread { if (viewer == null && broadcaster == null) bridge.monitorStart() } }
        stopService(Intent(this, LiveService::class.java))
        lastWStats = ""; netLine = ""
        findViewById<TextView>(R.id.wStats).text = ""
        findViewById<View>(R.id.wStats).visibility = View.GONE
    }

    // ================= CALL 1:1 =================
    private var callRole = "host"

    /** Encaminha as superfícies conforme o swap: por omissão o remoto enche o
     *  ecrã e a câmara vive no PiP; trocado, é o inverso. As views não se
     *  movem — só o conteúdo (o decoder rebuilda no keyframe seguinte, a
     *  câmara reconstrói a sessão de captura; a transmissão nunca para). */
    private fun routeCallSurfaces() {
        val rsv = findViewById<SurfaceView>(R.id.callRemoteSurface)
        val lsv = findViewById<SurfaceView>(R.id.callLocalSurface)
        val camSv = if (callSwapped) rsv else lsv   // superfície com a CÂMARA
        val vidSv = if (callSwapped) lsv else rsv   // superfície com o REMOTO
        // O buffer da câmara TEM de ter o tamanho de captura (como no live):
        // sem setFixedSize o stream é dimensionado pelo tamanho do view — no
        // swap para fullscreen o preview ficava esticado/deformado.
        camSv.holder.setFixedSize(callW, callH)
        vidSv.holder.setSizeFromLayout() // o decoder gere a própria geometria
        // espelho cosmético da frontal (só no preview local, nunca no remoto)
        camSv.scaleX = if (!callIsScreen && mirrorOn() && isFrontId(callCamId)) -1f else 1f
        vidSv.scaleX = 1f
        val camS = camSv.holder.surface
        val vidS = vidSv.holder.surface
        callViewer?.setVideoSurface(if (vidS?.isValid == true) vidS else null)
        callBroadcaster?.setPreviewSurface(if (camS?.isValid == true) camS else null)
        applyCallAspects()
    }

    /** Aspect da câmara no ecrã (buffer do sensor vs rotação do display). */
    private fun callCamAspect(): Pair<Int, Int> {
        val rel = (callSensor - displayRotationDeg() + 360) % 360
        return if (rel == 90 || rel == 270) Pair(callH, callW) else Pair(callW, callH)
    }

    /** Aspect do ecrã cheio + tamanho do PiP (segue o conteúdo e a escala). */
    private fun applyCallAspects() {
        val (fw, fh) = if (callSwapped) callCamAspect() else Pair(callRemoteW, callRemoteH)
        findViewById<AspectFrameLayout>(R.id.callRemoteBox).setAspect(fw, fh)
        val (pw, ph) = if (callSwapped) Pair(callRemoteW, callRemoteH) else callCamAspect()
        val pip = findViewById<View>(R.id.callLocalBox)
        val maxDim = (128 * PIP_SCALES[pipScaleIdx] * resources.displayMetrics.density).toInt()
        val lp = pip.layoutParams
        if (pw >= ph) { lp.width = maxDim; lp.height = maxDim * ph / pw }
        else { lp.height = maxDim; lp.width = maxDim * pw / ph }
        pip.layoutParams = lp
    }

    /** PiP: toque=swap, duplo toque=tamanho, arrastar=mover; pinch fora do PiP
     *  faz zoom da câmara quando o NOSSO preview está em fullscreen. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCallGestures() {
        val pip = findViewById<View>(R.id.callLocalBox)
        val page = findViewById<View>(R.id.pageCall)
        val pipGest = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                callSwapped = !callSwapped
                routeCallSurfaces()
                applyCallCamPh() // a cobre segue a superfície com a câmara
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                pipScaleIdx = (pipScaleIdx + 1) % PIP_SCALES.size
                applyCallAspects()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                pip.translationX = (pip.translationX - dx).coerceIn(-pip.left.toFloat(), (page.width - pip.right).toFloat())
                pip.translationY = (pip.translationY - dy).coerceIn(-pip.top.toFloat(), (page.height - pip.bottom).toFloat())
                return true
            }
        })
        pip.setOnTouchListener { _, ev -> pipGest.onTouchEvent(ev); true }
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (callSwapped) {
                    callZoom = (callZoom * d.scaleFactor).coerceIn(callMinZoom, callMaxZoom)
                    callBroadcaster?.setZoom(callZoom)
                }
                return true
            }
        })
        // toque simples no ecrã da chamada = mostrar/esconder overlay (como no LIVE)
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isConsoleOpen()) toggleConsole() else setCallControlsVisible(!controlsVisible)
                return true
            }
        })
        page.setOnTouchListener { _, ev -> scale.onTouchEvent(ev); tap.onTouchEvent(ev); true }
    }

    /** Troca frontal↔traseira sem quebrar a transmissão (chamada). */
    /** Toque LONGO no botão de trocar câmara: menu com TODAS as câmaras do
     *  dispositivo (o toque curto mantém a troca rápida frontal↔traseira). */
    private fun showCameraMenu(anchor: View, current: String?, onPick: (String) -> Unit) {
        if (camOpts.isEmpty()) camOpts = enumCameras()
        val cams = camOpts.filter { it.first.isNotEmpty() }
        if (cams.size < 2) return
        val pm = android.widget.PopupMenu(this, anchor)
        cams.forEachIndexed { i, c -> pm.menu.add(0, i, i, c.second + if (c.first == current) "  ✓" else "") }
        pm.setOnMenuItemClickListener { mi -> onPick(cams[mi.itemId].first); true }
        pm.show()
    }

    private fun flipCallCamera() {
        val curFront = camOpts.firstOrNull { it.first == callCamId }?.second?.startsWith("Front") == true
        val next = camOpts.firstOrNull { it.first.isNotEmpty() && it.second.startsWith("Front") != curFront } ?: return
        callCamId = next.first
        callZoom = 1f
        callBroadcaster?.switchCamera(next.first)
        resetCallTorch() // a lanterna não sobrevive à troca de câmara
        routeCallSurfaces() // atualiza o espelho da frontal
    }

    /** CÂMARA OFF/ON na chamada (paridade com o Meeting): off fecha a fonte de
     *  vídeo (só áudio publica) e mostra placeholder; on reabre a câmara. */
    private fun toggleCallCam() {
        if (callBroadcaster == null) return
        if (!callCamOff) {
            callCamOff = true
            callBroadcaster?.stopVideoSource()
        } else {
            callCamOff = false
            callBroadcaster?.setVideoSource(callCamId, null, callW, callH)
        }
        styleToggle(R.id.cCamBtn, callCamOff, if (callCamOff) R.drawable.ic_cam_off else R.drawable.ic_cam)
        findViewById<View>(R.id.cFlipBtn).visibility = if (callCamOff) View.GONE else View.VISIBLE
        resetCallTorch() // câmara off apaga a lanterna e esconde o botão
        applyCallCamPh()
        publishCallCam() // avisa o par (mostra placeholder do nosso lado nele)
    }

    /** Envia o estado da nossa câmara ao par pela partição de áudio (flag 0xC3).
     *  Repete algumas vezes: a mensagem é única (sem keyframe) e pode perder-se. */
    private fun publishCallCam() {
        val json = org.json.JSONObject().put("t", "cam").put("on", !callCamOff).toString()
        for (i in 0..2) ui.postDelayed({ callBroadcaster?.publishControl(json) }, i * 400L)
    }

    /** Cobres de câmara-cortada. A superfície principal mostra o REMOTO (normal) ou
     *  a NOSSA câmara (com swap); o PiP mostra o inverso — a cobre segue quem tem a
     *  câmara cortada em cada superfície. */
    private fun applyCallCamPh() {
        val mainOff = if (callSwapped) callCamOff else callPeerCamOff
        val pipOff = if (callSwapped) callPeerCamOff else callCamOff
        findViewById<View>(R.id.cMainPh).visibility = if (mainOff) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cLocalPh).visibility = if (pipOff) View.VISIBLE else View.GONE
    }

    /** Botão de lanterna (Call): visível sempre que a câmara está ativa (nem ecrã
     *  nem off) — na traseira usa o LED, na frontal o LED traseiro (livre). */
    private fun refreshCallTorchBtn() {
        findViewById<View>(R.id.cTorchBtn).visibility =
            if (!callIsScreen && !callCamOff) View.VISIBLE else View.GONE
    }
    private fun resetCallTorch() {
        callTorchOn = false
        if (screenFlashPage == R.id.pageCall) exitScreenFlash()
        styleToggle(R.id.cTorchBtn, false, R.drawable.ic_flash)
        refreshCallTorchBtn()
    }
    private fun refreshMeetTorchBtn() {
        findViewById<View>(R.id.meetTorchBtn).visibility =
            if (!meetIsScreen && !meetCamOff) View.VISIBLE else View.GONE
    }
    private fun resetMeetTorch() {
        meetTorchOn = false
        if (screenFlashPage == R.id.pageMeet) exitScreenFlash()
        styleToggle(R.id.meetTorchBtn, false, R.drawable.ic_flash)
        refreshMeetTorchBtn()
    }

    private fun startCall(role: String, screenProj: android.media.projection.MediaProjection? = null) {
        ensureNotifPerm()
        setCommAudioRoute(true) // voz no altifalante (mic VOICE_COMMUNICATION ativo)
        callRole = role
        bridge.callStart(role) // define partições cv/ca/rv/ra + proxies de publish
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        show("call")
        callMicMuted = false
        styleToggle(R.id.cMicBtn, false, R.drawable.ic_mic)
        findViewById<TextView>(R.id.cStatus).text = "$role · waiting for peer…"
        findViewById<View>(R.id.cSpinner).visibility = View.VISIBLE
        lastCStatsUp = ""; lastCStatsDn = ""; netLine = ""
        setCallControlsVisible(true)

        // config unificada (mesma página do LIVE): câmara ou partilha de ecrã
        if (camOpts.isEmpty()) camOpts = enumCameras()
        callIsScreen = cfgScreen && screenProj != null
        if (callIsScreen) {
            val (sw, sh) = screenCaptureDims(minOf(cfgW, cfgH))
            callW = sw; callH = sh; callSensor = 0; callCamId = null
        } else {
            callW = cfgW; callH = cfgH
            callCamId = cfgCamId ?: camOpts.firstOrNull { it.second.startsWith("Front") }?.first
                ?: camOpts.firstOrNull()?.first
        }
        val audioBr = cfgAudioBr
        // medidor uniforme: mostra a NOSSA resolução de emissão; ↑ upload / ↓ download
        resetMeter(up = true, res = resLabel(callW, callH))
        findViewById<View>(R.id.cFlipBtn).visibility = if (callIsScreen) View.GONE else View.VISIBLE
        callSwapped = false; callZoom = 1f; callMinZoom = 1f; callMaxZoom = 1f
        pipScaleIdx = 0; callRemoteW = 3; callRemoteH = 4
        // estado FIT/FILL + câmara-cortada limpo a cada chamada
        callFill = false; callCamOff = false; callPeerCamOff = false
        findViewById<TextView>(R.id.cFitBtn).text = "FIT"
        findViewById<AspectFrameLayout>(R.id.callRemoteBox).fillMode = false
        styleToggle(R.id.cCamBtn, false, R.drawable.ic_cam)
        applyCallCamPh()
        findViewById<View>(R.id.callLocalBox).apply { translationX = 0f; translationY = 0f }
        // buffer da câmara no tamanho de captura desde o arranque (aspect fiel)
        findViewById<SurfaceView>(R.id.callLocalSurface).holder.setFixedSize(callW, callH)
        applyCallAspects()

        // receção do par (rv/ra) — buffer curto: latência conversacional
        val rsv = findViewById<SurfaceView>(R.id.callRemoteSurface)
        val beginViewer = beginV@{
            if (callViewer != null) return@beginV
            callViewer = Viewer(
                bridge, rsv.holder.surface,
                kindVideo = "rv", kindAudio = "ra", baseTargetMs = 180, commAudio = true,
                onState = { s -> runOnUiThread {
                    findViewById<TextView>(R.id.cStatus).text = s
                    val waiting = s.contains("waiting") || s.contains("stabiliz") ||
                        s.contains("buffer") || s.contains("reconnect")
                    findViewById<View>(R.id.cSpinner).visibility = if (waiting) View.VISIBLE else View.GONE
                } },
                onStats = { s -> runOnUiThread { lastCStatsDn = "↓ peer · $s"; renderConsole() } },
                onVideoSize = { w, h -> runOnUiThread {
                    callRemoteW = w; callRemoteH = h; applyCallAspects()
                    // vídeo do par voltou (keyframe) ⇒ câmara ligada, mesmo que o
                    // flag "on" se tenha perdido
                    if (callPeerCamOff) { callPeerCamOff = false; applyCallCamPh() }
                } },
                // na chamada, res/bitrate/fps vêm do NOSSO emissor e o ↑/↓ do
                // tráfego real da ponte — este callback já não alimenta o medidor
                onMeter = { _, _, _ -> },
                onCamState = { on -> runOnUiThread { callPeerCamOff = !on; applyCallCamPh() } }
            ).also { it.encPassword = callPass; it.start() }
        }
        if (rsv.holder.surface?.isValid == true) beginViewer()
        else rsv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { beginViewer(); rsv.holder.removeCallback(this) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })

        // emissão própria (cv/ca) na qualidade escolhida, keyframe 1s
        val lsv = findViewById<SurfaceView>(R.id.callLocalSurface)
        val beginBcast = beginB@{
            if (callBroadcaster != null) return@beginB
            callBroadcaster = Broadcaster(
                this, bridge, if (callIsScreen) null else lsv.holder.surface, callW, callH, cfgBr, cfgKfMs,
                if (callIsScreen) null else callCamId, audioBr,
                kindVideo = "cv", kindAudio = "ca", manageOverlays = false,
                screenProjection = screenProj,
                onStats = { s -> runOnUiThread { lastCStatsUp = "↑ me · $s"; renderConsole() } },
                onError = { e -> runOnUiThread { toast(e); transientStatus(R.id.cStatus, e) } },
                onCameraInfo = { so, hasTorch, minZ, maxZ -> runOnUiThread {
                    callSensor = so; callMinZoom = minZ; callMaxZoom = maxZ
                    if (callZoom < minZ || callZoom > maxZ) callZoom = 1f
                    callHasTorch = hasTorch; refreshCallTorchBtn()
                    applyCallAspects()
                } },
                onMeter = { _, br, fps -> runOnUiThread { setMeterBr(br); setMeterFps(fps) } }
            ).also { it.encPassword = callPass; it.maxFps = cfgFps; it.displayRotationDeg = displayRotationDeg(); it.start() }
        }
        if (callIsScreen || lsv.holder.surface?.isValid == true) beginBcast()
        else lsv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { beginBcast(); lsv.holder.removeCallback(this) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun stopCall() {
        exitScreenFlash()
        enableScreenOrientation(false)
        setCommAudioRoute(false)
        val b = callBroadcaster; val v = callViewer
        if (b == null && v == null) return
        callBroadcaster = null; callViewer = null; callPass = null
        thread(name = "stop-call") {
            try { b?.stop() } catch (e: Exception) {}
            try { v?.stop() } catch (e: Exception) {} // desfaz rv/ra
            bridge.callStop()
        }
        stopService(Intent(this, LiveService::class.java))
        lastCStatsUp = ""; lastCStatsDn = ""; netLine = ""
        findViewById<TextView>(R.id.cStats).text = ""
        findViewById<View>(R.id.cStats).visibility = View.GONE
    }

    // ================= MEETING =================
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------- Definições EM SESSÃO (engrenagem do overlay) -----------------
    // Mesmas opções do menu inicial, aplicadas AO VIVO sem sair da transmissão.
    // bitrate/fps/áudio são imediatos; a resolução reconstrói o encoder (~0,5s,
    // os viewers re-sincronizam no keyframe seguinte). Não existe no LIVE watcher.
    /** >30fps só existe via sessão CONSTRAINED HIGH-SPEED, que depende da câmara
     *  E da resolução (neste telemóvel só 720p na traseira). A frontal está fora:
     *  não tem high-speed utilizável e a tentativa derruba o serviço de câmara. */
    private fun supportsHighFps(camId: String?, w: Int, h: Int): Boolean {
        val id = camId ?: return false
        return try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val ch = mgr.getCameraCharacteristics(id)
            if (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) return false
            val map = ch.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return false
            val size = android.util.Size(w, h)
            if (!map.highSpeedVideoSizes.contains(size)) return false
            map.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= 60 }
        } catch (e: Exception) { false }
    }

    /** Câmara em uso no modo atual (para saber o que o hardware suporta). */
    private fun currentCamId(): String? = when (currentPage) {
        "call" -> callCamId; "meet" -> meetCamId; else -> bcastCamId
    }

    private fun activeBroadcaster(): Broadcaster? = when (currentPage) {
        "broadcast" -> broadcaster
        "call" -> callBroadcaster
        "meet" -> meetBroadcaster
        else -> null
    }

    /** Muda a resolução em pleno live: reconstrói a fonte com as novas dimensões.
     *  CRÍTICO: o buffer do preview está fixo com setFixedSize — sem o atualizar,
     *  a câmara escreve o aspeto novo num buffer antigo (480p 4:3 ↔ 720p 16:9) e a
     *  imagem sai deformada. O surfaceChanged daí resultante refaz a sessão. */
    private fun applySessionRes(w: Int, h: Int) {
        android.util.Log.d("SessionCfg", "applySessionRes page=$currentPage → ${w}x${h}")
        when (currentPage) {
            "broadcast" -> { if (isScreenShare) return; bcastW = w; bcastH = h; bcastCamW = w; bcastCamH = h
                findViewById<SurfaceView>(R.id.previewSurface).holder.setFixedSize(w, h)
                broadcaster?.setVideoSource(bcastCamId, null, w, h); applyPreviewAspect() }
            "call" -> { if (callIsScreen) return; callW = w; callH = h
                findViewById<SurfaceView>(R.id.callLocalSurface).holder.setFixedSize(w, h)
                callBroadcaster?.setVideoSource(callCamId, null, w, h); applyCallAspects() }
            "meet" -> { if (meetIsScreen || meetCamOff) return; meetW = w; meetH = h
                findViewById<SurfaceView>(R.id.meetLocalSurface).holder.setFixedSize(w, h)
                meetBroadcaster?.setVideoSource(meetCamId, null, w, h); applyMeetLocalAspect() }
        }
        setMeterRes(resLabel(w, h))
    }

    private fun showSessionSettings() {
        val b = activeBroadcaster() ?: return
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        fun <T> spinner(items: List<String>, sel: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(sel, false)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

        // resolução (indisponível em partilha de ecrã / câmara off)
        val screenNow = (currentPage == "broadcast" && isScreenShare) ||
            (currentPage == "call" && callIsScreen) ||
            (currentPage == "meet" && (meetIsScreen || meetCamOff))
        val curW = when (currentPage) { "call" -> callW; "meet" -> meetW; else -> bcastW }
        val curH = when (currentPage) { "call" -> callH; "meet" -> meetH; else -> bcastH }
        // fps: só se oferece o que a câmara ATUAL suporta nesta resolução — a lista
        // é reconstruída quando a resolução muda (60 desaparece onde não existe).
        val fpsLabel = label("Frame rate")
        val fpsSpin = Spinner(this)
        var fpsOpts = SCREEN_FPS.filter { it <= 30 || supportsHighFps(currentCamId(), curW, curH) }
        fun bindFps(opts: List<Int>) {
            fpsOpts = opts
            fpsSpin.adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, opts.map { "$it fps" })
            fpsSpin.setSelection(opts.indexOf(sessionFps).coerceAtLeast(0), false)
            // dica DERIVADA do hardware: diz em que resoluções esta câmara faz 60
            // (ou que não faz de todo) em vez de assumir as deste telemóvel.
            fpsLabel.text = if (opts.size > 1) "Frame rate" else {
                val cam = currentCamId()
                val can = RES.filter { supportsHighFps(cam, it.second.first, it.second.second) }
                if (can.isEmpty()) "Frame rate · 60 fps not available on this camera"
                else "Frame rate · 60 fps needs " + can.joinToString("/") { it.first.substringBefore(" ") }
            }
        }

        box.addView(label(if (screenNow) "Resolution (locked while sharing screen)" else "Resolution"))
        box.addView(spinner<Int>(RES.map { it.first }, RES.indexOfFirst { it.second == Pair(curW, curH) }.coerceAtLeast(0)) { pos ->
            if (screenNow) return@spinner
            val nw = RES[pos].second.first; val nh = RES[pos].second.second
            applySessionRes(nw, nh)
            // a nova resolução pode não ter 60fps → cair para 30 e refazer a lista
            val ok = SCREEN_FPS.filter { it <= 30 || supportsHighFps(currentCamId(), nw, nh) }
            if (sessionFps !in ok) { sessionFps = 30; b.changeMaxFps(30); setMeterFps(30) }
            bindFps(ok)
        }.apply { isEnabled = !screenNow })

        // bitrate de vídeo — imediato
        val brLabel = label("Video bitrate")
        box.addView(brLabel)
        val curBrProg = ((sessionBr - 500_000) / 100_000).coerceIn(0, 75)
        brLabel.text = "Video bitrate · ${"%.1f".format(sessionBr / 1e6)} Mbps"
        box.addView(SeekBar(this).apply {
            max = 75; progress = curBrProg
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sessionBr = bitrateOf(p)
                    brLabel.text = "Video bitrate · ${"%.1f".format(sessionBr / 1e6)} Mbps"
                    if (fromUser) b.changeBitrate(sessionBr)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        // fps — imediato (lista já filtrada pelo que o hardware suporta)
        bindFps(fpsOpts)
        fpsSpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val f = fpsOpts.getOrNull(pos) ?: return
                if (f == sessionFps) return
                sessionFps = f; b.changeMaxFps(f); setMeterFps(f)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        box.addView(fpsLabel)
        box.addView(fpsSpin)

        // bitrate de áudio — reinicia só a thread de áudio
        box.addView(label("Audio bitrate"))
        box.addView(spinner<Int>(ABR.map { it.first },
            ABR.indexOfFirst { it.second == sessionAbr }.coerceAtLeast(0)) { pos ->
            sessionAbr = ABR[pos].second; b.changeAudioBitrate(sessionAbr)
        })

        android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
        bumpAutoHide()
    }

    // ---------------- Flash: LED (traseiro) ou Screen flash (ecrã branco) --------
    // A frontal não tem LED, por isso o "flash" dela ilumina a cara com o ECRÃ a
    // branco no máximo de brilho. Fonte por modo: AUTO (frontal→ecrã, traseira→LED)
    // ou forçada pelo dropdown de toque longo.
    private val FLASH_AUTO = 0; private val FLASH_LED = 1; private val FLASH_SCREEN = 2
    private var bcastFlashSrc = FLASH_AUTO
    private var callFlashSrc = FLASH_AUTO
    private var meetFlashSrc = FLASH_AUTO
    private var screenFlashPage = 0                      // pageId ativo, 0 = nenhum
    private val flashSaved = HashMap<View, Pair<Int, Int>>()

    /** true = usar o ecrã branco; false = LED traseiro. */
    private fun flashIsScreen(src: Int, front: Boolean) =
        src == FLASH_SCREEN || (src == FLASH_AUTO && front)

    /** Ecrã branco no máximo de brilho, com os previews encolhidos (o "buraco" da
     *  SurfaceView fica pequeno → mostra a câmara; o resto da página fica branco). */
    private fun enterScreenFlash(pageId: Int, previews: List<View>) {
        if (screenFlashPage == pageId) return
        exitScreenFlash() // sai de outro se estiver
        screenFlashPage = pageId
        val lp = window.attributes; lp.screenBrightness = 1f; window.attributes = lp
        findViewById<View>(pageId).setBackgroundColor(android.graphics.Color.WHITE)
        previews.forEach { v ->
            flashSaved[v] = v.layoutParams.width to v.layoutParams.height
            v.layoutParams = v.layoutParams.apply { width = dp(150); height = dp(210) }
        }
    }

    private fun exitScreenFlash() {
        if (screenFlashPage == 0) return
        val page = findViewById<View>(screenFlashPage)
        screenFlashPage = 0
        val lp = window.attributes
        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        // as três páginas (Broadcast/Call/Meet) são #000000 — repõe preto (as
        // barras de letterbox voltam a ser pretas em vez de brancas).
        page.setBackgroundColor(android.graphics.Color.BLACK)
        flashSaved.forEach { (v, wh) -> v.layoutParams = v.layoutParams.apply { width = wh.first; height = wh.second } }
        flashSaved.clear()
    }

    /** Dropdown de toque longo: escolher a fonte do flash. */
    private fun showFlashMenu(anchor: View, current: Int, onPick: (Int) -> Unit) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, FLASH_LED, 0, "LED (traseiro)" + if (current == FLASH_LED) "  ✓" else "")
        pm.menu.add(0, FLASH_SCREEN, 1, "Screen flash" + if (current == FLASH_SCREEN) "  ✓" else "")
        pm.setOnMenuItemClickListener { mi -> onPick(mi.itemId); true }
        pm.show()
    }

    private fun applyBcastFlash() {
        val front = camOpts.firstOrNull { it.first == bcastCamId }?.second?.startsWith("Front") == true
        if (flashIsScreen(bcastFlashSrc, front)) {
            broadcaster?.setTorch(false)
            if (torchOn) enterScreenFlash(R.id.pageBroadcast, listOf(findViewById(R.id.previewBox)))
            else exitScreenFlash()
        } else {
            exitScreenFlash()
            broadcaster?.setTorch(torchOn)
        }
        styleToggle(R.id.torchBtn, torchOn, R.drawable.ic_flash)
    }

    private fun applyCallFlash() {
        val front = camOpts.firstOrNull { it.first == callCamId }?.second?.startsWith("Front") == true
        if (flashIsScreen(callFlashSrc, front)) {
            callBroadcaster?.setTorch(false)
            if (callTorchOn) enterScreenFlash(R.id.pageCall, listOf(findViewById(R.id.callRemoteBox)))
            else exitScreenFlash()
        } else {
            exitScreenFlash()
            callBroadcaster?.setTorch(callTorchOn)
        }
        styleToggle(R.id.cTorchBtn, callTorchOn, R.drawable.ic_flash)
    }

    private fun applyMeetFlash() {
        val front = camOpts.firstOrNull { it.first == meetCamId }?.second?.startsWith("Front") == true
        if (flashIsScreen(meetFlashSrc, front)) {
            meetBroadcaster?.setTorch(false)
            if (meetTorchOn) enterScreenFlash(R.id.pageMeet, listOf(findViewById(R.id.meetLocalAspect)))
            else exitScreenFlash()
        } else {
            exitScreenFlash()
            meetBroadcaster?.setTorch(meetTorchOn)
        }
        styleToggle(R.id.meetTorchBtn, meetTorchOn, R.drawable.ic_flash)
    }

    private fun startMeeting(screenProj: android.media.projection.MediaProjection? = null, camOff: Boolean = false) {
        ensureNotifPerm()
        setCommAudioRoute(true) // voz no altifalante (mic VOICE_COMMUNICATION ativo)
        // fonte=ecrã: o FGS mediaProjection já foi arrancado pelo onActivityResult
        if (screenProj == null) startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        show("meet")
        // mic OFF por omissão num meeting (etiqueta social); mutado continua a
        // emitir silêncio opus — o relógio de áudio dos peers não pára
        meetMicMuted = true; meetCamOff = camOff; meetIsScreen = screenProj != null
        meetPendingProj = screenProj
        styleToggle(R.id.meetMicBtn, true, R.drawable.ic_mic_off)
        styleToggle(R.id.meetCamBtn, meetCamOff, if (meetCamOff) R.drawable.ic_cam_off else R.drawable.ic_cam)
        findViewById<View>(R.id.meetFlipBtn).visibility = if (meetCamOff || meetIsScreen) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.meetLocalLab).text = "you"
        findViewById<TextView>(R.id.meetStats).text = ""
        lastMeetStats = ""; netLine = ""
        setMeetControlsVisible(true)
        clearMeetTiles()

        // config unificada (mesma página do LIVE)
        if (meetIsScreen) {
            val (w, h) = screenCaptureDims(minOf(cfgW, cfgH))
            meetW = w; meetH = h; meetSensor = 0
        } else { meetW = cfgW; meetH = cfgH }
        // medidor uniforme: NOSSA resolução; ↑ upload / ↓ download total (soma feeds)
        resetMeter(up = true, res = resLabel(meetW, meetH))
        if (camOpts.isEmpty()) camOpts = enumCameras()
        meetCamId = cfgCamId ?: camOpts.firstOrNull { it.second.startsWith("Front") }?.first
            ?: camOpts.firstOrNull()?.first
        findViewById<SurfaceView>(R.id.meetLocalSurface).holder.setFixedSize(meetW, meetH)
        findViewById<View>(R.id.meetScroll).visibility = View.VISIBLE
        findViewById<AspectFrameLayout>(R.id.meetLocalAspect).fillMode = meetFill
        applyMeetSpot() // tranca o tile local a UMA célula (sem pesos/esticões)
        applyMeetLocalPh(); applyMeetLocalAspect()

        meeting = MeetingEngine(
            bridge,
            myId = bridgeAddr ?: java.util.UUID.randomUUID().toString(),
            onPeerAdded = { s -> runOnUiThread { addMeetTile(s) } },
            onPeerRemoved = { s -> runOnUiThread { removeMeetTile(s) } },
            // barra de topo só mostra estado/erros — o "in meeting · slot #N" foi
            // retirado (o slot já vai no medidor/consola e no rótulo do tile local)
            onState = { s -> runOnUiThread {
                findViewById<TextView>(R.id.meetStatus).text = if (s.startsWith("in meeting")) "" else s
            } },
            onSlotChosen = { s -> runOnUiThread {
                findViewById<TextView>(R.id.meetLocalLab).text = "you · #$s"
                startMeetBroadcaster(cfgBr)
            } },
            onStats = { s -> runOnUiThread { lastMeetStats = s; renderConsole() } },
            // download total = soma do rx de todos os feeds (o upload vem do emissor)
            // no meeting, res/bitrate/fps vêm do NOSSO emissor e o ↑/↓ do tráfego
            // real da ponte (soma de todos os peers) — não deste callback
            onMeter = { _ -> },
            onPeerCam = { s, cam -> runOnUiThread { meetTiles[s]?.ph?.visibility = if (cam) View.GONE else View.VISIBLE } },
            onPeerVideoSize = { s, w, h -> runOnUiThread {
                meetTiles[s]?.let { t -> t.aw = w; t.ah = h; t.aspect.setAspect(w, h) }
                if (meetSpotFs && meetSpotSlot == s) findViewById<AspectFrameLayout>(R.id.meetFsAspect).setAspect(w, h)
            } },
            onPeerFit = { s, fill -> runOnUiThread {
                meetTiles[s]?.let { t -> if (t.fill != fill) { t.fill = fill; t.aspect.fillMode = fill } }
                if (meetSpotFs && meetSpotSlot == s) findViewById<AspectFrameLayout>(R.id.meetFsAspect).fillMode = fill
            } },
            onParticipants = { n -> runOnUiThread { applyMeetAdaptiveBitrate(n) } })
        if (camOff) meeting?.setCam(false)
        meeting?.setFill(meetFill)
        meeting?.begin()
        if (meetIsScreen) enableScreenOrientation(true)
    }

    /** Bitrate adaptativo (meeting): cada recetor recebe (N−1) streams — o
     *  emissor divide o seu teto pelos (N−1) para o download agregado de cada
     *  um caber no orçamento (~2.5Mbps), em vez de crescer e saturar o proxy.
     *  ON/OFF pela pref meetAdapt. */
    private fun applyMeetAdaptiveBitrate(n: Int) {
        val b = meetBroadcaster ?: return
        val others = n - 1 // participantes que ME recebem
        val ceiling = if (!prefs().getBoolean("meetAdapt", true) || others < 1) meetBrBase
        else minOf(meetBrBase, MEET_DL_BUDGET / others)
        b.setBitrateCeiling(ceiling)
        android.util.Log.d("Meeting", "adaptive bitrate: N=$n → teto=${ceiling / 1000}kbps (base=${meetBrBase / 1000})")
    }

    /** Emissor próprio — arranca depois de o slot estar escolhido (o kind 'ms'
     *  já mapeado + proxies aplicados pelo bridgeMeetStart). */
    private fun startMeetBroadcaster(bitrate: Int) {
        if (meetBroadcaster != null) return
        meetBrBase = bitrate
        val audioBr = cfgAudioBr
        val lsv = findViewById<SurfaceView>(R.id.meetLocalSurface)
        val begin = b@{
            if (meetBroadcaster != null || meeting?.active != true) return@b
            meetBroadcaster = Broadcaster(
                this, bridge, lsv.holder.surface, meetW, meetH, bitrate, cfgKfMs, meetCamId, audioBr,
                kindVideo = "ms", kindAudio = "ms", manageOverlays = false, singlePartition = true,
                screenProjection = meetPendingProj, startWithVideo = !meetCamOff,
                onStats = { s -> meeting?.setSelfStats(s) },
                onMeter = { _, br, fps -> runOnUiThread { setMeterBr(br); setMeterFps(fps) } },
                onError = { e -> runOnUiThread { toast(e) } },
                onCameraInfo = { so, hasTorch, _, _ -> runOnUiThread { meetSensor = so; meetHasTorch = hasTorch; refreshMeetTorchBtn(); applyMeetLocalAspect() } }
            ).also { it.maxFps = cfgFps; it.displayRotationDeg = displayRotationDeg(); it.setMicMuted(meetMicMuted); it.start() }
            meetPendingProj = null
        }
        if (lsv.holder.surface?.isValid == true) begin()
        else lsv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { begin(); lsv.holder.removeCallback(this) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    /** Aspect do preview local: câmara = buffer do sensor transposto pela
     *  rotação relativa (como no call/broadcast); ecrã = dims da captura. */
    private fun meetLocalAspectDims(): Pair<Int, Int> {
        if (meetIsScreen || meetSensor == 0) return Pair(meetW, meetH)
        val rel = (meetSensor - displayRotationDeg() + 360) % 360
        val t = rel == 90 || rel == 270
        return Pair(if (t) meetH else meetW, if (t) meetW else meetH)
    }

    private fun applyMeetLocalAspect() {
        val (w, h) = meetLocalAspectDims()
        findViewById<AspectFrameLayout>(R.id.meetLocalAspect).setAspect(w, h)
        if (meetSpotFs && meetSpotSlot == -1)
            findViewById<AspectFrameLayout>(R.id.meetFsAspect).setAspect(w, h)
        applyMeetLocalMirror()
    }

    // ---- DESTAQUE: toque = 2×2; 2º toque = fullscreen; toque no fs = volta ----
    private fun meetTileTap(s: Int) {
        if (meeting == null) return
        setMeetControlsVisible(true) // tocar num tile também revela/renova o overlay
        if (meetSpotSlot == s && !meetSpotFs) { enterMeetFs(s); return }
        meetSpotSlot = s
        applyMeetSpot()
    }

    /** Largura de UMA célula da grelha (2 colunas) — FIXA, sem pesos: um tile
     *  sozinho na linha fica "trancado" à célula em vez de esticar. */
    private fun meetCellW(): Int =
        (resources.displayMetrics.widthPixels - dp(16) - dp(16)) / 2

    private fun setTileBig(v: View, big: Boolean) {
        val lp = v.layoutParams as? android.widget.GridLayout.LayoutParams ?: return
        val cell = meetCellW()
        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, if (big) 2 else 1)
        lp.rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED)
        lp.width = if (big) cell * 2 + dp(8) else cell
        lp.height = dp(if (big) 308 else 150)
        v.layoutParams = lp
    }

    private fun applyMeetSpot() {
        setTileBig(findViewById(R.id.meetLocalTile), meetSpotSlot == -1)
        for ((s, t) in meetTiles) setTileBig(t.root, meetSpotSlot == s)
    }

    private fun enterMeetFs(s: Int) {
        meetSpotFs = true
        val a = findViewById<AspectFrameLayout>(R.id.meetFsAspect)
        val fsSv = findViewById<SurfaceView>(R.id.meetFsSurface)
        // o enquadramento é o do EMISSOR (o meu se for o meu tile)
        a.fillMode = if (s == -1) meetFill else (meetTiles[s]?.fill ?: false)
        if (s == -1) {
            val (w, h) = meetLocalAspectDims(); a.setAspect(w, h)
            // MEU preview (câmara): o buffer da surface TEM de ser o tamanho de
            // captura (meetW×meetH) — o sistema roda-o para a view. Sem isto o
            // Camera2 estica o buffer para a view fullscreen (imagem deformada).
            fsSv.holder.setFixedSize(meetW, meetH)
        } else {
            val t = meetTiles[s]; a.setAspect(t?.aw ?: 4, t?.ah ?: 3)
            // peer: o MediaCodec renderiza ao coded size — a surface segue a view
            fsSv.holder.setSizeFromLayout()
        }
        // a grelha esconde-se: as surfaces dos tiles morrem (as de outros peers
        // param de descodificar — poupança) e nada fica visível atrás do fs
        findViewById<View>(R.id.meetScroll).visibility = View.GONE
        findViewById<View>(R.id.meetFsBox).visibility = View.VISIBLE
        routeMeetFs()
    }

    /** Encaminha o vídeo do destacado para a surface de fullscreen (quando válida). */
    private fun routeMeetFs() {
        if (!meetSpotFs) return
        val s = meetSpotSlot ?: return
        val fs = findViewById<SurfaceView>(R.id.meetFsSurface).holder.surface
        if (fs?.isValid != true) return
        if (s == -1) meetBroadcaster?.setPreviewSurface(fs)
        else meeting?.viewers?.get(s)?.setVideoSurface(fs)
    }

    /** Sai do fullscreen E do destaque (volta à grelha normal). O re-encaminhar
     *  do vídeo para o tile é EXPLÍCITO: num toque-toque rápido o GONE/VISIBLE
     *  da grelha coalesce numa só passagem de layout, a SurfaceView do tile
     *  nunca é recriada e o surfaceCreated (que religava) não dispara — o viewer
     *  ficava a renderizar para a surface morta do fs (imagem congelada com
     *  stats saudáveis). Se o tile ainda não tiver surface válida, o
     *  surfaceCreated cobre o resto. */
    private fun exitMeetSpot() {
        val s = meetSpotSlot
        val wasFs = meetSpotFs
        meetSpotFs = false; meetSpotSlot = null
        findViewById<View>(R.id.meetFsBox).visibility = View.GONE
        findViewById<View>(R.id.meetScroll).visibility = View.VISIBLE
        if (wasFs && s != null) {
            if (s == -1) {
                val ls = findViewById<SurfaceView>(R.id.meetLocalSurface).holder.surface
                meetBroadcaster?.setPreviewSurface(if (ls?.isValid == true) ls else null)
            } else {
                val ts = meetTiles[s]?.sv?.holder?.surface
                meeting?.viewers?.get(s)?.setVideoSurface(if (ts?.isValid == true) ts else null)
            }
        }
        applyMeetSpot()
    }

    /** Placeholder do tile local: câmara off = ic_cam_off; a partilhar ecrã =
     *  ic_screen (o espelho de si próprio seria recursivo). */
    private fun applyMeetLocalPh() {
        val ph = findViewById<View>(R.id.meetLocalPh)
        val icon = findViewById<android.widget.ImageView>(R.id.meetLocalPhIcon)
        when {
            meetCamOff -> { icon.setImageResource(R.drawable.ic_cam_off); ph.visibility = View.VISIBLE }
            meetIsScreen -> { icon.setImageResource(R.drawable.ic_screen); ph.visibility = View.VISIBLE }
            else -> ph.visibility = View.GONE
        }
    }

    /** CÂMARA OFF/ON: off fecha a fonte de vídeo (LED apaga, só áudio publica)
     *  e o hb cam:false põe o placeholder nos peers; on reabre a câmara. */
    private fun toggleMeetCam() {
        if (meeting == null) return
        if (!meetCamOff) {
            meetCamOff = true; meetIsScreen = false
            enableScreenOrientation(false)
            meetBroadcaster?.stopVideoSource()
            meeting?.setCam(false)
        } else {
            meetCamOff = false
            meetW = cfgW; meetH = cfgH
            meetBroadcaster?.setVideoSource(meetCamId, null, meetW, meetH)
            meeting?.setCam(true)
        }
        styleToggle(R.id.meetCamBtn, meetCamOff, if (meetCamOff) R.drawable.ic_cam_off else R.drawable.ic_cam)
        findViewById<View>(R.id.meetFlipBtn).visibility = if (meetCamOff) View.GONE else View.VISIBLE
        resetMeetTorch() // câmara off apaga a lanterna e esconde o botão
        applyMeetLocalPh(); applyMeetLocalAspect()
    }

    private fun switchMeetToScreen(mp: android.media.projection.MediaProjection) {
        meetIsScreen = true
        if (meetCamOff) { meetCamOff = false; styleToggle(R.id.meetCamBtn, false, R.drawable.ic_cam) }
        val (w, h) = screenCaptureDims(minOf(cfgW, cfgH))
        meetW = w; meetH = h; meetSensor = 0
        meetBroadcaster?.setVideoSource(null, mp, w, h)
        meeting?.setCam(true) // ecrã é vídeo — os peers tiram o placeholder
        findViewById<View>(R.id.meetFlipBtn).visibility = View.GONE
        resetMeetTorch() // sem lanterna na partilha de ecrã
        applyMeetLocalPh(); applyMeetLocalAspect()
        enableScreenOrientation(true)
    }

    private fun switchMeetToCamera() {
        meetIsScreen = false
        enableScreenOrientation(false)
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        meetW = cfgW; meetH = cfgH
        val camId = meetCamId ?: camOpts.firstOrNull { it.second.startsWith("Front") }?.first
        meetCamId = camId
        meetBroadcaster?.setVideoSource(camId, null, meetW, meetH)
        findViewById<View>(R.id.meetFlipBtn).visibility = View.VISIBLE
        refreshMeetTorchBtn() // volta o botão (se a câmara tiver flash)
        applyMeetLocalPh() // o aspect volta pelo onCameraInfo quando a câmara abrir
    }

    private fun stopMeeting() {
        exitScreenFlash()
        val m = meeting ?: return
        meeting = null
        setCommAudioRoute(false)
        val b = meetBroadcaster; meetBroadcaster = null
        enableScreenOrientation(false)
        meetIsScreen = false; meetCamOff = false; meetPendingProj = null
        meetSpotSlot = null; meetSpotFs = false
        findViewById<View>(R.id.meetFsBox).visibility = View.GONE
        findViewById<View>(R.id.meetScroll).visibility = View.VISIBLE
        setTileBig(findViewById(R.id.meetLocalTile), false)
        m.stop() // envia leave, para os viewers e faz bridge.meetStop em background
        thread(name = "stop-meet-bcast") { try { b?.stop() } catch (e: Exception) {} }
        stopService(Intent(this, LiveService::class.java))
        clearMeetTiles()
        applyMeetLocalPh()
        lastMeetStats = ""; netLine = ""
        findViewById<TextView>(R.id.meetStats).text = ""
        findViewById<View>(R.id.meetStats).visibility = View.GONE
        bridge.monitorStart()
    }

    /** Troca frontal↔traseira sem quebrar a emissão (meeting). */
    private fun flipMeetCamera() {
        if (meetCamOff || meetIsScreen) return
        val curFront = camOpts.firstOrNull { it.first == meetCamId }?.second?.startsWith("Front") == true
        val next = camOpts.firstOrNull { it.first.isNotEmpty() && it.second.startsWith("Front") != curFront } ?: return
        meetCamId = next.first
        meetBroadcaster?.switchCamera(next.first)
        resetMeetTorch() // a lanterna não sobrevive à troca de câmara
    }

    /** Tile dinâmico de um slot remoto: AspectFrameLayout (aspect/orientação
     *  fiéis — o Viewer aplica KEY_ROTATION e reporta as dims de render) +
     *  SurfaceView + placeholder de câmara off + etiqueta. Grelha 2 colunas. */
    private fun addMeetTile(s: Int) {
        if (meetTiles.containsKey(s)) return
        val grid = findViewById<android.widget.GridLayout>(R.id.meetGrid)
        val tile = android.widget.FrameLayout(this)
        val lp = android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED))
        lp.width = meetCellW(); lp.height = dp(150); lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        tile.layoutParams = lp
        tile.setBackgroundResource(R.drawable.bg_card)
        val aspect = AspectFrameLayout(this)
        aspect.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER)
        aspect.setAspect(4, 3) // até o 1º config do stream chegar; o fillMode
        // é o do EMISSOR e chega pelo hb (onPeerFit)
        val sv = SurfaceView(this)
        sv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        aspect.addView(sv)
        tile.addView(aspect)
        // placeholder = cobre OPACA (tapa a última frame congelada quando o peer
        // desliga a câmara) + ícone centrado
        val ph = android.widget.FrameLayout(this)
        ph.setBackgroundColor(0xFF000000.toInt())
        ph.visibility = View.GONE
        ph.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        val phIcon = android.widget.ImageView(this)
        phIcon.setImageResource(R.drawable.ic_cam_off)
        phIcon.alpha = 0.7f
        phIcon.layoutParams = android.widget.FrameLayout.LayoutParams(dp(44), dp(44), android.view.Gravity.CENTER)
        ph.addView(phIcon)
        tile.addView(ph)
        val lab = TextView(this)
        lab.text = "#$s"
        lab.setTextColor(0xFFFFFFFF.toInt()); lab.textSize = 10f
        lab.typeface = android.graphics.Typeface.MONOSPACE
        lab.setBackgroundResource(R.drawable.bg_chip)
        lab.setPadding(dp(6), dp(2), dp(6), dp(2))
        val ll = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        ll.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
        ll.setMargins(dp(6), 0, 0, dp(6))
        lab.layoutParams = ll
        tile.addView(lab)
        grid.addView(tile)
        meetTiles[s] = MeetTile(tile, aspect, ph, sv)
        tile.setOnClickListener { meetTileTap(s) }
        if (meetSpotSlot == s) setTileBig(tile, true) // peer voltou já destacado
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (meetSpotFs && meetSpotSlot == s) return // o vídeo está no fullscreen
                val v = meeting?.viewers?.get(s)
                if (v != null) v.setVideoSurface(h.surface) else meeting?.attachViewer(s, h.surface)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (meetSpotFs && meetSpotSlot == s) return
                meeting?.viewers?.get(s)?.setVideoSurface(null)
            }
        })
    }

    private fun removeMeetTile(s: Int) {
        val t = meetTiles.remove(s) ?: return
        if (meetSpotSlot == s) { // o destacado saiu da reunião
            meetSpotSlot = null; meetSpotFs = false
            findViewById<View>(R.id.meetFsBox).visibility = View.GONE
            applyMeetSpot()
        }
        findViewById<android.widget.GridLayout>(R.id.meetGrid).removeView(t.root)
    }

    private fun clearMeetTiles() {
        for (s in meetTiles.keys.toList()) removeMeetTile(s)
    }

    // ================= NOTIFICATION ACTIONS =================
    /** Atualiza os toggles da notificação (botões da app E ações da notificação);
     *  usa NotificationManager.notify via o serviço, seguro em background. */
    private fun refreshNotif() {
        LiveService.updateNotif(
            micMuted = when (currentPage) { "call" -> callMicMuted; "meet" -> meetMicMuted; else -> micMuted },
            soundMuted = volMuted,
            paused = viewerPaused)
    }

    /** Executa uma ação vinda da notificação, no contexto da sessão atual. */
    private fun handleNotifAction(act: String) {
        when (act) {
            LiveService.ACT_STOP -> when (currentPage) {
                "broadcast" -> { stopBroadcast(); show("home") }
                "watch" -> { stopWatch(); show("home") }
                "call" -> { stopCall(); show("home") }
                "meet" -> { stopMeeting(); show("home") }
            }
            LiveService.ACT_MUTE_MIC -> when (currentPage) {
                "broadcast" -> findViewById<ImageButton>(R.id.micBtn).performClick()
                "call" -> findViewById<ImageButton>(R.id.cMicBtn).performClick()
                "meet" -> findViewById<ImageButton>(R.id.meetMicBtn).performClick()
            }
            LiveService.ACT_MUTE_SOUND -> if (currentPage == "watch")
                findViewById<ImageButton>(R.id.muteBtn).performLongClick() // toggle mute
            LiveService.ACT_PAUSE -> if (currentPage == "watch")
                findViewById<ImageButton>(R.id.ppBtn).performClick()
        }
    }

    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    /** Console = engine stats + overlay line (proxies/mesh) from the bridge. */
    private fun renderConsole() {
        val suffix = if (netLine.isEmpty()) "" else "\n$netLine"
        if (lastBStats.isNotEmpty()) findViewById<TextView>(R.id.bStats).text = lastBStats + suffix
        if (lastWStats.isNotEmpty()) findViewById<TextView>(R.id.wStats).text = lastWStats + suffix
        val cStats = listOf(lastCStatsUp, lastCStatsDn).filter { it.isNotEmpty() }.joinToString("\n")
        if (cStats.isNotEmpty()) findViewById<TextView>(R.id.cStats).text = cStats + suffix
        if (lastMeetStats.isNotEmpty()) findViewById<TextView>(R.id.meetStats).text = lastMeetStats + suffix
    }

    override fun onBridgeLiveState(live: Boolean) {
        liveNow = live
        findViewById<View>(R.id.liveBadge).visibility = if (live) View.VISIBLE else View.GONE
    }

    /** Tráfego REAL do transporte (soma de TODAS as ligações de peer): é isto que
     *  o medidor mostra em qualquer modo. O payload de publish/subscribe conta uma
     *  vez só e escondia o fan-out da malha (emissor a servir N vizinhos) e o
     *  reencaminhamento de quem só vê (aparecia com 0 de upload). */
    override fun onBridgeTraffic(upMbps: Double, downMbps: Double, conns: Int) {
        setMeterTx(upMbps); setMeterRx(downMbps)
    }

    override fun onBridgeNetInfo(json: String) {
        netLine = try {
            val o = org.json.JSONObject(json)
            val parts = ArrayList<String>()
            // itera todas as chaves (video/audio nos outros modos; slot#N e ctrl#9
            // no meeting) → contador de proxies consistente em todas as consolas
            for (kind in o.keys()) {
                val k = o.optJSONObject(kind) ?: continue
                val p = k.optInt("proxy", 0)
                val m = k.optInt("mesh", -1)
                parts.add("$kind=" + when {
                    p > 0 -> "proxy×$p"
                    m >= 0 -> "mesh($m nbrs)"
                    else -> "mesh"
                })
            }
            if (parts.isEmpty()) "" else "net: " + parts.joinToString(" · ")
        } catch (e: Exception) { "" }
        renderConsole()
    }

    // ================= bridge =================
    private fun setStatus(s: String, connectedDot: Boolean) {
        findViewById<TextView>(R.id.status).text = s
        findViewById<TextView>(R.id.statusDot).setTextColor(
            ContextCompat.getColor(this, if (connectedDot) R.color.ok else R.color.warn))
        // bridge state is also surfaced on the session pages (top bar)
        if (currentPage == "watch") findViewById<TextView>(R.id.wStatus).text = s
        if (currentPage == "broadcast") findViewById<TextView>(R.id.bStatus).text = s
    }

    override fun onBridgeStatus(status: String) = setStatus(status, connectedDot = false)

    override fun onBridgeConnected(address: String) {
        bridgeAddr = address.lowercase() // id de presença do meeting (tiebreak por string)
        setStatus("connected · ${address.take(10)}…", connectedDot = true)
        findViewById<TextView>(R.id.netAddr).text = "address: $address\nstream: ${bridge.streamId}"
        // The JS client is fresh (first connect or reconnection) — push the
        // settings and restore the active session's subscriptions.
        pushProxyCounts()
        if (viewer != null) { bridge.subscribe("video"); bridge.subscribe("audio") }
        if (broadcaster != null) { bridge.join("video"); bridge.join("audio") }
        // chamada ativa num mundo JS renascido: repor partições/proxies e subs
        if (callViewer != null || callBroadcaster != null) {
            bridge.callStart(callRole)
            if (callViewer != null) { bridge.subscribe("rv"); bridge.subscribe("ra") }
        }
        // meeting ativo num mundo JS renascido: repor controlo/slot/subscrições
        meeting?.resubscribe()
        if (viewer == null && broadcaster == null && callViewer == null && callBroadcaster == null && meeting == null)
            bridge.monitorStart()
    }

    override fun onBridgeMessage(kind: String, payload: ByteArray) {
        // background thread; os Viewers sincronizam internamente
        when {
            kind == "rv" || kind == "ra" -> callViewer?.onMessage(kind, payload)
            kind.startsWith("p") -> meeting?.onMedia(kind, payload)
            else -> viewer?.onMessage(kind, payload)
        }
    }

    override fun onBridgeMeetCtrl(json: String) { meeting?.onCtrl(json) }

    override fun onBridgeMeetSlotBlocked() { meeting?.onSlotBlocked() }

    override fun onBridgeError(message: String) = setStatus(message, connectedDot = false)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        LiveService.actionHandler = null
        try { orientationListener.disable() } catch (e: Exception) {}
        stopBroadcast()
        stopWatch()
        stopCall()
        stopService(Intent(this, LiveService::class.java))
        if (::bridge.isInitialized) bridge.destroy()
        super.onDestroy()
    }
}
