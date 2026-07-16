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

    private lateinit var pages: Map<String, View>
    private var currentPage = "home"
    private var bcastW = 1280
    private var bcastH = 720
    private var bcastSensor = 90
    private var lastNetworkId: String? = null
    private var lastReconnectMs = 0L
    private val RES = listOf("480p (640x480)" to Pair(640, 480), "720p (1280x720)" to Pair(1280, 720), "1080p (1920x1080)" to Pair(1920, 1080))
    private val ABR = listOf("32 kbps" to 32_000, "64 kbps" to 64_000, "128 kbps" to 128_000)
    private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val REQ_PERMS = 1
    private val REQ_NOTIF = 2
    private var camOpts: List<Pair<String, String>> = emptyList() // camera id → label
    private var netLine = ""      // overlay info (proxies/mesh) appended to the consoles
    private var lastBStats = ""
    private var lastWStats = ""

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
            "watch" to findViewById(R.id.pageWatch)
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

        findViewById<Button>(R.id.goLive).setOnClickListener {
            if (!bridge.connected) { toast("Still connecting to Streamr…"); return@setOnClickListener }
            // one broadcast at a time — the audio monitor keeps liveNow current
            if (liveNow) { toast("A live broadcast is already on air — join as a viewer."); return@setOnClickListener }
            if (hasPerms()) show("settings")
            else ActivityCompat.requestPermissions(this, PERMS, REQ_PERMS)
        }
        findViewById<Button>(R.id.goWatch).setOnClickListener {
            if (!bridge.connected) { toast("Still connecting to Streamr…"); return@setOnClickListener }
            startWatch()
        }
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
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        bridge = StreamrBridge(this, this)

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
                findViewById<View>(R.id.bControls).visibility = if (vis) View.VISIBLE else View.GONE
                findViewById<View>(R.id.bTopBar).visibility = if (vis) View.VISIBLE else View.GONE
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
        }
        findViewById<ImageButton>(R.id.torchBtn).setOnClickListener {
            torchOn = !torchOn
            broadcaster?.setTorch(torchOn)
            styleToggle(R.id.torchBtn, torchOn, R.drawable.ic_flash)
        }
        findViewById<ImageButton>(R.id.bConsoleBtn).setOnClickListener { toggle(R.id.bStats) }
        findViewById<ImageButton>(R.id.wConsoleBtn).setOnClickListener { toggle(R.id.wStats); bumpAutoHide() }

        findViewById<ImageButton>(R.id.fsBtn).setOnClickListener { setFullscreen(!fullscreen); bumpAutoHide() }

        val vol = findViewById<SeekBar>(R.id.volumeBar)
        vol.progress = prefs().getInt("volume", 100)
        vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                volMuted = false
                applyVolume()
                bumpAutoHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { prefs().edit().putInt("volume", sb.progress).apply() }
        })
        findViewById<ImageButton>(R.id.muteBtn).setOnClickListener {
            volMuted = !volMuted
            applyVolume()
            bumpAutoHide()
        }
        findViewById<ImageButton>(R.id.ppBtn).setOnClickListener {
            viewerPaused = !viewerPaused
            viewer?.setPaused(viewerPaused)
            findViewById<ImageButton>(R.id.ppBtn).setImageResource(
                if (viewerPaused) R.drawable.ic_play else R.drawable.ic_pause)
            // pausado: controlos ficam visíveis; a retomar volta o auto-esconder
            if (viewerPaused) ui.removeCallbacks(hideControls) else bumpAutoHide()
        }
    }

    private fun setZoom(ratio: Float) {
        curZoom = ratio.coerceIn(minZoom, maxZoom)
        broadcaster?.setZoom(curZoom)
        findViewById<TextView>(R.id.zoomChip).text = "%.1f×".format(curZoom)
    }

    private fun applyVolume() {
        val p = if (volMuted) 0 else findViewById<SeekBar>(R.id.volumeBar).progress
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
        findViewById<View>(R.id.wControls).visibility = if (vis) View.VISIBLE else View.GONE
        findViewById<View>(R.id.wTopBar).visibility = if (vis) View.VISIBLE else View.GONE
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

    private fun pushProxyCounts() {
        val p = prefs()
        bridge.setProxyCounts(p.getInt("proxyPub", 2), p.getInt("proxySub", 2))
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
        val resSel = findViewById<Spinner>(R.id.resSel)
        resSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, RES.map { it.first })
        resSel.setSelection(1) // 720p

        camOpts = enumCameras()
        val camSel = findViewById<Spinner>(R.id.camSel)
        camSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, camOpts.map { it.second })

        val audioSel = findViewById<Spinner>(R.id.audioSel)
        audioSel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ABR.map { it.first })
        audioSel.setSelection(prefs().getInt("audioBr", 1)) // default 64 kbps

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
    }

    private fun show(page: String) {
        currentPage = page
        pages.forEach { (k, v) -> v.visibility = if (k == page) View.VISIBLE else View.GONE }
        ui.removeCallbacks(timerTick)
        ui.removeCallbacks(hideControls)
        if (page == "broadcast" || page == "watch") {
            sessionStartMs = android.os.SystemClock.elapsedRealtime()
            ui.post(timerTick)
        } else if (fullscreen) setFullscreen(false)
    }

    // ================= BROADCAST =================
    private fun startBroadcast() {
        val (w, h) = RES[findViewById<Spinner>(R.id.resSel).selectedItemPosition].second
        val camPos = findViewById<Spinner>(R.id.camSel).selectedItemPosition
        val camId = camOpts.getOrNull(camPos)?.first?.ifEmpty { null }
        val bitrate = bitrateOf(findViewById<SeekBar>(R.id.bitrate).progress)
        val kfMs = kfOf(findViewById<SeekBar>(R.id.kf).progress)
        val abrPos = findViewById<Spinner>(R.id.audioSel).selectedItemPosition
        val audioBitrate = ABR.getOrNull(abrPos)?.second ?: 64_000
        prefs().edit().putInt("audioBr", abrPos).apply()

        ensureNotifPerm()
        bridge.monitorStop() // the session takes over the audio subscription
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "broadcast"))
        show("broadcast")
        micMuted = false; torchOn = false; curZoom = 1f; minZoom = 1f; maxZoom = 1f
        styleToggle(R.id.micBtn, false, R.drawable.ic_mic)
        styleToggle(R.id.torchBtn, false, R.drawable.ic_flash)
        findViewById<TextView>(R.id.zoomChip).text = "1.0×"
        findViewById<View>(R.id.bControls).visibility = View.VISIBLE
        findViewById<View>(R.id.bTopBar).visibility = View.VISIBLE
        findViewById<TextView>(R.id.bStatus).text = ""
        findViewById<TextView>(R.id.bMeter).text = "${h}p · %.1f Mbps".format(bitrate / 1e6)
        bcastW = w; bcastH = h
        applyPreviewAspect()

        val sv = findViewById<SurfaceView>(R.id.previewSurface)
        sv.holder.setFixedSize(w, h)
        val begin = begin@{
            if (broadcaster != null) return@begin
            broadcaster = Broadcaster(
                this, bridge, sv.holder.surface, w, h, bitrate, kfMs, camId, audioBitrate,
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
            ).also { it.displayRotationDeg = displayRotationDeg(); it.start() }
        }
        if (sv.holder.surface?.isValid == true) begin()
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
        if (currentPage == "broadcast") {
            applyPreviewAspect()
            // viewers endireitam pelo rotation dos keyframes — mantê-lo atual
            broadcaster?.displayRotationDeg = displayRotationDeg()
        }
    }

    private fun stopBroadcast() {
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
    private fun startWatch() {
        ensureNotifPerm()
        bridge.monitorStop() // the session takes over the audio subscription
        startForegroundService(Intent(this, LiveService::class.java).putExtra("mode", "watch"))
        show("watch")
        viewerPaused = false
        findViewById<ImageButton>(R.id.ppBtn).setImageResource(R.drawable.ic_pause)
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
            ).also { it.start(); applyVolume() }
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
    }

    override fun onBridgeLiveState(live: Boolean) {
        liveNow = live
        findViewById<View>(R.id.liveBadge).visibility = if (live) View.VISIBLE else View.GONE
    }

    override fun onBridgeNetInfo(json: String) {
        netLine = try {
            val o = org.json.JSONObject(json)
            val parts = ArrayList<String>()
            for (kind in listOf("video", "audio")) {
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
        setStatus("connected · ${address.take(10)}…", connectedDot = true)
        // The JS client is fresh (first connect or reconnection) — push the
        // settings and restore the active session's subscriptions.
        pushProxyCounts()
        if (viewer != null) { bridge.subscribe("video"); bridge.subscribe("audio") }
        if (broadcaster != null) { bridge.join("video"); bridge.join("audio") }
        if (viewer == null && broadcaster == null) bridge.monitorStart()
    }

    override fun onBridgeMessage(kind: String, payload: ByteArray) {
        viewer?.onMessage(kind, payload) // background thread; Viewer synchronizes
    }

    override fun onBridgeError(message: String) = setStatus(message, connectedDot = false)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopBroadcast()
        stopWatch()
        stopService(Intent(this, LiveService::class.java))
        if (::bridge.isInitialized) bridge.destroy()
        super.onDestroy()
    }
}
