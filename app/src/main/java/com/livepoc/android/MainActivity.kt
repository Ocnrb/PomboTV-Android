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
    private var pendingCallRole: String? = null
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
    private var camOpts: List<Pair<String, String>> = emptyList() // camera id → label
    private var netLine = ""      // overlay info (proxies/mesh) appended to the consoles
    private var lastBStats = ""
    private var lastWStats = ""
    private var lastMeetStats = ""

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
    private val hideControls = Runnable { if (currentPage == "watch") setWatchControlsVisible(false) }
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
            override fun surfaceCreated(h: SurfaceHolder) { broadcaster?.setPreviewSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { broadcaster?.setPreviewSurface(null) }
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

        findViewById<Button>(R.id.goLive).setOnClickListener {
            if (!bridge.connected) { toast("Still connecting to Streamr…"); return@setOnClickListener }
            // one broadcast at a time — the audio monitor keeps liveNow current
            if (liveNow) { toast("A live broadcast is already on air — join as a viewer."); return@setOnClickListener }
            if (hasPerms()) show("settings")
            else ActivityCompat.requestPermissions(this, PERMS, REQ_PERMS)
        }
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
        findViewById<Button>(R.id.goCall).setOnClickListener {
            if (!bridge.connected) { toast("Still connecting to Streamr…"); return@setOnClickListener }
            // sem sinalização: o primeiro entra como Host, o segundo como Guest.
            // O diálogo escolhe também a qualidade (vídeo/áudio), persistida.
            val p = prefs()
            val view = layoutInflater.inflate(R.layout.dialog_call, null)
            val qSel = view.findViewById<Spinner>(R.id.callQualitySel)
            qSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CALL_Q.map { it.first })
            qSel.setSelection(p.getInt("callQ", 1))
            val aSel = view.findViewById<Spinner>(R.id.callAudioSel)
            aSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CALL_A.map { it.first })
            aSel.setSelection(p.getInt("callA", 0))
            val begin = { role: String ->
                p.edit().putInt("callQ", qSel.selectedItemPosition).putInt("callA", aSel.selectedItemPosition).apply()
                callPass = view.findViewById<android.widget.EditText>(R.id.callPassEdit)
                    .text.toString().ifEmpty { null }
                if (hasPerms()) startCall(role)
                else { pendingCallRole = role; ActivityCompat.requestPermissions(this, PERMS, REQ_CALL) }
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Call · 1:1")
                .setView(view)
                .setPositiveButton("Start (Host)") { _, _ -> begin("host") }
                .setNegativeButton("Join (Guest)") { _, _ -> begin("guest") }
                .setNeutralButton("Cancel", null)
                .show()
        }
        findViewById<Button>(R.id.goMeet).setOnClickListener {
            if (!bridge.connected) { toast("Still connecting to Streamr…"); return@setOnClickListener }
            val p = prefs()
            val view = layoutInflater.inflate(R.layout.dialog_meet, null)
            val sSel = view.findViewById<Spinner>(R.id.meetSourceSel)
            sSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MEET_SRC)
            sSel.setSelection(p.getInt("meetSrc", 0))
            val qSel = view.findViewById<Spinner>(R.id.meetQualitySel)
            qSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MEET_Q.map { it.first })
            qSel.setSelection(p.getInt("meetQ", 1))
            val aSel = view.findViewById<Spinner>(R.id.meetAudioSel)
            aSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, CALL_A.map { it.first })
            aSel.setSelection(p.getInt("meetA", 0))
            val adaptCb = view.findViewById<android.widget.CheckBox>(R.id.meetAdaptCb)
            adaptCb.isChecked = p.getBoolean("meetAdapt", true)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Meeting")
                .setView(view)
                .setPositiveButton("Join") { _, _ ->
                    p.edit().putInt("meetSrc", sSel.selectedItemPosition)
                        .putInt("meetQ", qSel.selectedItemPosition)
                        .putInt("meetA", aSel.selectedItemPosition)
                        .putBoolean("meetAdapt", adaptCb.isChecked).apply()
                    val srcIdx = sSel.selectedItemPosition
                    if (hasPerms()) launchMeeting(srcIdx)
                    else { pendingMeet = true; pendingMeetSrc = srcIdx; ActivityCompat.requestPermissions(this, PERMS, REQ_MEET) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<ImageButton>(R.id.meetMicBtn).setOnClickListener {
            meetMicMuted = !meetMicMuted
            meetBroadcaster?.setMicMuted(meetMicMuted)
            styleToggle(R.id.meetMicBtn, meetMicMuted, if (meetMicMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
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
        findViewById<ImageButton>(R.id.meetConsoleBtn).setOnClickListener { toggle(R.id.meetStats) }
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
            override fun surfaceCreated(h: SurfaceHolder) {
                if (!(meetSpotFs && meetSpotSlot == -1)) meetBroadcaster?.setPreviewSurface(h.surface)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (!(meetSpotFs && meetSpotSlot == -1)) meetBroadcaster?.setPreviewSurface(null)
            }
        })
        findViewById<ImageButton>(R.id.cFlipBtn).setOnClickListener { flipCallCamera() }
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
        findViewById<ImageButton>(R.id.cConsoleBtn).setOnClickListener { toggle(R.id.cStats) }
        findViewById<Button>(R.id.cEndBtn).setOnClickListener { stopCall(); show("home") }
        findViewById<ImageButton>(R.id.gearBtn).setOnClickListener { show("network") }
        findViewById<ImageButton>(R.id.netBack).setOnClickListener { show("home") }
        findViewById<Button>(R.id.settingsBack).setOnClickListener { show("home") }
        findViewById<Button>(R.id.startBtn).setOnClickListener { startBroadcast() }
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
        findViewById<View>(R.id.pageWatch).setOnClickListener { setWatchControlsVisible(!controlsVisible) }

        val scaleDet = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(curZoom * d.scaleFactor); return true }
        })
        val tapDet = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val vis = findViewById<View>(R.id.bControls).visibility != View.VISIBLE
                val v = if (vis) View.VISIBLE else View.GONE
                findViewById<View>(R.id.bControls).visibility = v
                findViewById<View>(R.id.bTopBar).visibility = v
                findViewById<View>(R.id.bConsoleBtn).visibility = v
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
            broadcaster?.setTorch(torchOn)
            styleToggle(R.id.torchBtn, torchOn, R.drawable.ic_flash)
        }
        findViewById<ImageButton>(R.id.bFlipBtn).setOnClickListener {
            // frontal↔traseira em pleno live — o encoder não para (switchCamera);
            // torch/zoom não sobrevivem à troca, repor a UI
            val curFront = camOpts.firstOrNull { it.first == bcastCamId }?.second?.startsWith("Front") == true
            val next = camOpts.firstOrNull { it.first.isNotEmpty() && it.second.startsWith("Front") != curFront }
                ?: return@setOnClickListener
            bcastCamId = next.first
            torchOn = false
            styleToggle(R.id.torchBtn, false, R.drawable.ic_flash)
            curZoom = 1f
            findViewById<TextView>(R.id.zoomChip).text = "1.0×"
            broadcaster?.switchCamera(next.first)
            applyBroadcastMirror()
        }
        findViewById<ImageButton>(R.id.bConsoleBtn).setOnClickListener { toggle(R.id.bStats) }
        findViewById<ImageButton>(R.id.wConsoleBtn).setOnClickListener { toggle(R.id.wStats); bumpAutoHide() }

        findViewById<ImageButton>(R.id.fsBtn).setOnClickListener { setFullscreen(!fullscreen); bumpAutoHide() }

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

    private fun setWatchControlsVisible(vis: Boolean) {
        controlsVisible = vis
        val v = if (vis) View.VISIBLE else View.GONE
        findViewById<View>(R.id.wControls).visibility = v
        findViewById<View>(R.id.wTopBar).visibility = v
        findViewById<View>(R.id.fsBtn).visibility = v
        // o slider de volume só aparece por ação explícita no ícone de som;
        // ao esconder os controlos, esconde-se também
        if (!vis) findViewById<View>(R.id.volPanel).visibility = View.GONE
        if (vis) bumpAutoHide() else ui.removeCallbacks(hideControls)
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
        findViewById<ImageButton>(R.id.fsBtn).setImageResource(
            if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    // ============== settings ==============
    private fun prefs() = getSharedPreferences("livepoc", Context.MODE_PRIVATE)

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
        bridge.setProxyCounts(p.getInt("proxyPub", 2), p.getInt("proxySub", 2))
        // modos de rede: fast start (malha→proxy), proxy-only (malha proibida),
        // single-partition (áudio+vídeo na #0; a #1 é ignorada)
        bridge.setModes(p.getBoolean("meshStart", false), p.getBoolean("proxyOnly", false),
            p.getBoolean("onePart", false))
    }

    private fun setupProxySelectors() {
        val opts = listOf("0 (mesh)", "1", "2", "3")
        val p = prefs()
        for ((id, key) in listOf(R.id.proxyPubSel to "proxyPub", R.id.proxySubSel to "proxySub")) {
            val sel = findViewById<Spinner>(id)
            sel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
            sel.setSelection(p.getInt(key, 2))
            sel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, idL: Long) {
                    if (p.getInt(key, 2) != pos) {
                        p.edit().putInt(key, pos).apply()
                        pushProxyCounts()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        for ((id, key) in listOf(R.id.meshStartSw to "meshStart", R.id.proxyOnlySw to "proxyOnly", R.id.onePartSw to "onePart")) {
            val sw = findViewById<android.widget.Switch>(id)
            sw.isChecked = p.getBoolean(key, false)
            sw.setOnCheckedChangeListener { _, checked ->
                p.edit().putBoolean(key, checked).apply()
                pushProxyCounts()
            }
        }
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

        // modos experimentais de transporte (persistidos); mux implica onePart
        // espelho cosmético do preview frontal (aplica ao vivo se possível)
        val mirrorCb = findViewById<android.widget.CheckBox>(R.id.mirrorCb)
        mirrorCb.isChecked = prefs().getBoolean("mirrorFront", false)
        mirrorCb.setOnCheckedChangeListener { _, c ->
            prefs().edit().putBoolean("mirrorFront", c).apply()
            applyBroadcastMirror()
        }

        val brLabel = findViewById<TextView>(R.id.brLabel)
        findViewById<SeekBar>(R.id.bitrate).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                brLabel.text = "Bitrate %.1f Mbps".format(bitrateOf(p) / 1e6)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        val kfLabel = findViewById<TextView>(R.id.kfLabel)
        findViewById<SeekBar>(R.id.kf).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                kfLabel.text = "Keyframe %.1f s".format(kfOf(p) / 1000.0)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun bitrateOf(progress: Int) = 500_000 + progress * 100_000       // 0.5–8.0 Mbps
    private fun kfOf(progress: Int) = 500 + progress * 250                    // 0.5–4.0 s

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
        if (requestCode !in listOf(REQ_SCREEN, REQ_SCREEN_SWITCH, REQ_SCREEN_CALL, REQ_SCREEN_MEET, REQ_SCREEN_MEET_SWITCH)) return
        if (resultCode != RESULT_OK || data == null) { toast("Screen share cancelled."); return }
        // Android 14+: o FGS de tipo mediaProjection TEM de estar ativo ANTES
        // de obter o MediaProjection — (re)arranca o serviço e espera-o assentar
        ensureNotifPerm()
        val svcMode = if (requestCode in listOf(REQ_SCREEN_CALL, REQ_SCREEN_MEET, REQ_SCREEN_MEET_SWITCH)) "call-screen" else "screen"
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
        routeCallSurfaces() // re-fixa o buffer + aspects + espelho
        enableScreenOrientation(true)
    }

    private fun switchCallToCamera() {
        callIsScreen = false
        enableScreenOrientation(false)
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        val q = CALL_Q.getOrNull(prefs().getInt("callQ", 1))?.second ?: Triple(640, 480, 1_200_000)
        callW = q.first; callH = q.second
        val camId = callCamId ?: camOpts.firstOrNull { it.second.startsWith("Front") }?.first
        callCamId = camId
        callBroadcaster?.setVideoSource(camId, null, callW, callH)
        findViewById<View>(R.id.cFlipBtn).visibility = View.VISIBLE
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
        val kfMs = kfOf(findViewById<SeekBar>(R.id.kf).progress)
        val abrPos = findViewById<Spinner>(R.id.audioSel).selectedItemPosition
        val audioBitrate = ABR.getOrNull(abrPos)?.second ?: 64_000
        // single-partition é um modo GLOBAL (página Network), não do broadcast
        val onePart = prefs().getBoolean("onePart", false)
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
        findViewById<View>(R.id.bConsoleBtn).visibility = View.VISIBLE
        findViewById<View>(R.id.stopBtn).visibility = View.VISIBLE
        findViewById<TextView>(R.id.bStatus).text = if (isScreenShare) "sharing screen" else ""
        findViewById<TextView>(R.id.bMeter).text = "${h}p · %.1f Mbps".format(bitrate / 1e6)
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
                singlePartition = onePart,
                screenProjection = projection,
                onStats = { s -> runOnUiThread { lastBStats = s; renderConsole() } },
                onError = { e -> runOnUiThread { toast(e); findViewById<TextView>(R.id.bStatus).text = e } },
                onCameraInfo = { so, hasTorch, minZ, maxZ -> runOnUiThread {
                    bcastSensor = so; applyPreviewAspect()
                    minZoom = minZ; maxZoom = maxZ
                    findViewById<View>(R.id.torchBtn).visibility = if (hasTorch) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.zoomChip).visibility =
                        if (maxZ > 1.05f || minZ < 0.95f) View.VISIBLE else View.GONE
                } },
                onMeter = { tx, br -> runOnUiThread {
                    findViewById<TextView>(R.id.bMeter).text = "${h}p · %.1f Mbps · ↑%.2f".format(br, tx)
                } }
            ).also {
                it.encPassword = findViewById<android.widget.EditText>(R.id.bPassEdit)
                    .text.toString().ifEmpty { null }
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
        findViewById<TextView>(R.id.wMeter).text = ""
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
                onStats = { s -> runOnUiThread { lastWStats = s; renderConsole() } },
                onVideoSize = { w, h -> runOnUiThread { findViewById<AspectFrameLayout>(R.id.watchBox).setAspect(w, h) } },
                onMeter = { rx -> runOnUiThread { findViewById<TextView>(R.id.wMeter).text = "↓ %.2f Mbps".format(rx) } }
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
        page.setOnTouchListener { _, ev -> scale.onTouchEvent(ev); true }
    }

    /** Troca frontal↔traseira sem quebrar a transmissão (chamada). */
    private fun flipCallCamera() {
        val curFront = camOpts.firstOrNull { it.first == callCamId }?.second?.startsWith("Front") == true
        val next = camOpts.firstOrNull { it.first.isNotEmpty() && it.second.startsWith("Front") != curFront } ?: return
        callCamId = next.first
        callZoom = 1f
        callBroadcaster?.switchCamera(next.first)
        routeCallSurfaces() // atualiza o espelho da frontal
    }

    private fun startCall(role: String) {
        ensureNotifPerm()
        callRole = role
        bridge.callStart(role) // define partições cv/ca/rv/ra + proxies de publish
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        show("call")
        callMicMuted = false
        styleToggle(R.id.cMicBtn, false, R.drawable.ic_mic)
        findViewById<TextView>(R.id.cStatus).text = "$role · waiting for peer…"
        findViewById<View>(R.id.cSpinner).visibility = View.VISIBLE

        // qualidade escolhida no diálogo + câmara FRONTAL por omissão
        val q = CALL_Q.getOrNull(prefs().getInt("callQ", 1))?.second ?: Triple(640, 480, 1_200_000)
        callW = q.first; callH = q.second
        val audioBr = CALL_A.getOrNull(prefs().getInt("callA", 0))?.second ?: 32_000
        if (camOpts.isEmpty()) camOpts = enumCameras()
        callCamId = camOpts.firstOrNull { it.second.startsWith("Front") }?.first
            ?: camOpts.firstOrNull()?.first
        // estado do swap/PiP limpo a cada chamada
        callIsScreen = false
        findViewById<View>(R.id.cFlipBtn).visibility = View.VISIBLE
        callSwapped = false; callZoom = 1f; callMinZoom = 1f; callMaxZoom = 1f
        pipScaleIdx = 0; callRemoteW = 3; callRemoteH = 4
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
                kindVideo = "rv", kindAudio = "ra", baseTargetMs = 250,
                onState = { s -> runOnUiThread {
                    findViewById<TextView>(R.id.cStatus).text = s
                    val waiting = s.contains("waiting") || s.contains("stabiliz") ||
                        s.contains("buffer") || s.contains("reconnect")
                    findViewById<View>(R.id.cSpinner).visibility = if (waiting) View.VISIBLE else View.GONE
                } },
                onStats = { s -> runOnUiThread { findViewById<TextView>(R.id.cStats).text = s } },
                onVideoSize = { w, h -> runOnUiThread { callRemoteW = w; callRemoteH = h; applyCallAspects() } }
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
                this, bridge, lsv.holder.surface, callW, callH, q.third, 1000, callCamId, audioBr,
                kindVideo = "cv", kindAudio = "ca", manageOverlays = false,
                onStats = {},
                onError = { e -> runOnUiThread { toast(e); findViewById<TextView>(R.id.cStatus).text = e } },
                onCameraInfo = { so, _, minZ, maxZ -> runOnUiThread {
                    callSensor = so; callMinZoom = minZ; callMaxZoom = maxZ
                    if (callZoom < minZ || callZoom > maxZ) callZoom = 1f
                    applyCallAspects()
                } }
            ).also { it.encPassword = callPass; it.displayRotationDeg = displayRotationDeg(); it.start() }
        }
        if (lsv.holder.surface?.isValid == true) beginBcast()
        else lsv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { beginBcast(); lsv.holder.removeCallback(this) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun stopCall() {
        enableScreenOrientation(false)
        val b = callBroadcaster; val v = callViewer
        if (b == null && v == null) return
        callBroadcaster = null; callViewer = null; callPass = null
        thread(name = "stop-call") {
            try { b?.stop() } catch (e: Exception) {}
            try { v?.stop() } catch (e: Exception) {} // desfaz rv/ra
            bridge.callStop()
        }
        stopService(Intent(this, LiveService::class.java))
        findViewById<TextView>(R.id.cStats).text = ""
        findViewById<View>(R.id.cStats).visibility = View.GONE
    }

    // ================= MEETING =================
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startMeeting(screenProj: android.media.projection.MediaProjection? = null, camOff: Boolean = false) {
        ensureNotifPerm()
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
        clearMeetTiles()

        val q = MEET_Q.getOrNull(prefs().getInt("meetQ", 1))?.second ?: Triple(640, 480, 500_000)
        if (meetIsScreen) {
            val (w, h) = screenCaptureDims(minOf(q.first, q.second))
            meetW = w; meetH = h; meetSensor = 0
        } else { meetW = q.first; meetH = q.second }
        if (camOpts.isEmpty()) camOpts = enumCameras()
        meetCamId = camOpts.firstOrNull { it.second.startsWith("Front") }?.first
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
            onState = { s -> runOnUiThread { findViewById<TextView>(R.id.meetStatus).text = s } },
            onSlotChosen = { s -> runOnUiThread {
                findViewById<TextView>(R.id.meetLocalLab).text = "you · #$s"
                startMeetBroadcaster(q.third)
            } },
            onStats = { s -> runOnUiThread { lastMeetStats = s; renderConsole() } },
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
        val audioBr = CALL_A.getOrNull(prefs().getInt("meetA", 0))?.second ?: 32_000
        val lsv = findViewById<SurfaceView>(R.id.meetLocalSurface)
        val begin = b@{
            if (meetBroadcaster != null || meeting?.active != true) return@b
            meetBroadcaster = Broadcaster(
                this, bridge, lsv.holder.surface, meetW, meetH, bitrate, 1000, meetCamId, audioBr,
                kindVideo = "ms", kindAudio = "ms", manageOverlays = false, singlePartition = true,
                screenProjection = meetPendingProj, startWithVideo = !meetCamOff,
                onStats = {},
                onError = { e -> runOnUiThread { toast(e) } },
                onCameraInfo = { so, _, _, _ -> runOnUiThread { meetSensor = so; applyMeetLocalAspect() } }
            ).also { it.displayRotationDeg = displayRotationDeg(); it.setMicMuted(meetMicMuted); it.start() }
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
    }

    // ---- DESTAQUE: toque = 2×2; 2º toque = fullscreen; toque no fs = volta ----
    private fun meetTileTap(s: Int) {
        if (meeting == null) return
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
        val ph = findViewById<android.widget.ImageView>(R.id.meetLocalPh)
        when {
            meetCamOff -> { ph.setImageResource(R.drawable.ic_cam_off); ph.visibility = View.VISIBLE }
            meetIsScreen -> { ph.setImageResource(R.drawable.ic_screen); ph.visibility = View.VISIBLE }
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
            val q = MEET_Q.getOrNull(prefs().getInt("meetQ", 1))?.second ?: Triple(640, 480, 500_000)
            meetW = q.first; meetH = q.second
            meetBroadcaster?.setVideoSource(meetCamId, null, meetW, meetH)
            meeting?.setCam(true)
        }
        styleToggle(R.id.meetCamBtn, meetCamOff, if (meetCamOff) R.drawable.ic_cam_off else R.drawable.ic_cam)
        findViewById<View>(R.id.meetFlipBtn).visibility = if (meetCamOff) View.GONE else View.VISIBLE
        applyMeetLocalPh(); applyMeetLocalAspect()
    }

    private fun switchMeetToScreen(mp: android.media.projection.MediaProjection) {
        meetIsScreen = true
        if (meetCamOff) { meetCamOff = false; styleToggle(R.id.meetCamBtn, false, R.drawable.ic_cam) }
        val q = MEET_Q.getOrNull(prefs().getInt("meetQ", 1))?.second ?: Triple(640, 480, 500_000)
        val (w, h) = screenCaptureDims(minOf(q.first, q.second))
        meetW = w; meetH = h; meetSensor = 0
        meetBroadcaster?.setVideoSource(null, mp, w, h)
        meeting?.setCam(true) // ecrã é vídeo — os peers tiram o placeholder
        findViewById<View>(R.id.meetFlipBtn).visibility = View.GONE
        applyMeetLocalPh(); applyMeetLocalAspect()
        enableScreenOrientation(true)
    }

    private fun switchMeetToCamera() {
        meetIsScreen = false
        enableScreenOrientation(false)
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "call"))
        val q = MEET_Q.getOrNull(prefs().getInt("meetQ", 1))?.second ?: Triple(640, 480, 500_000)
        meetW = q.first; meetH = q.second
        val camId = meetCamId ?: camOpts.firstOrNull { it.second.startsWith("Front") }?.first
        meetCamId = camId
        meetBroadcaster?.setVideoSource(camId, null, meetW, meetH)
        findViewById<View>(R.id.meetFlipBtn).visibility = View.VISIBLE
        applyMeetLocalPh() // o aspect volta pelo onCameraInfo quando a câmara abrir
    }

    private fun stopMeeting() {
        val m = meeting ?: return
        meeting = null
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
        val ph = android.widget.ImageView(this)
        ph.setImageResource(R.drawable.ic_cam_off)
        ph.alpha = 0.7f
        ph.visibility = View.GONE
        ph.layoutParams = android.widget.FrameLayout.LayoutParams(dp(44), dp(44), android.view.Gravity.CENTER)
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
        if (lastMeetStats.isNotEmpty()) findViewById<TextView>(R.id.meetStats).text = lastMeetStats + suffix
    }

    override fun onBridgeLiveState(live: Boolean) {
        liveNow = live
        findViewById<View>(R.id.liveBadge).visibility = if (live) View.VISIBLE else View.GONE
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
