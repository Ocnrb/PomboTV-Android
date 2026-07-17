package com.livepoc.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that keeps a live session (broadcast/watch/call) alive when
 * the app is backgrounded: without it Android kills camera/microphone access and
 * eventually the process. Holds a partial wake lock so encode/playback threads
 * keep running with the screen off.
 *
 * The ongoing notification carries per-mode ACTIONS (mute mic, mute sound, pause,
 * end). Taps on an action come back as service intents with setAction(...); the
 * service relays them to MainActivity (same process) via [actionHandler], which
 * performs the real toggle and re-issues the notification to reflect the new
 * state. Tapping the notification body brings the app to the foreground.
 */
class LiveService : Service() {

    companion object {
        const val ACT_MUTE_MIC = "com.livepoc.MUTE_MIC"
        const val ACT_MUTE_SOUND = "com.livepoc.MUTE_SOUND"
        const val ACT_PAUSE = "com.livepoc.PAUSE"
        const val ACT_STOP = "com.livepoc.STOP"
        /** Set by MainActivity (same process) to receive notification-action taps. */
        @Volatile var actionHandler: ((String) -> Unit)? = null
        @Volatile private var instance: LiveService? = null
        /** Atualiza os toggles da notificação sem re-arrancar o FGS (seguro em
         *  background, ao contrário de startForegroundService). */
        fun updateNotif(micMuted: Boolean, soundMuted: Boolean, paused: Boolean) {
            val s = instance ?: return
            s.micMuted = micMuted; s.soundMuted = soundMuted; s.paused = paused
            try {
                s.getSystemService(NotificationManager::class.java)
                    .notify(1, s.buildNotification())
            } catch (e: Exception) {}
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var mode = "watch"
    private var micMuted = false
    private var soundMuted = false
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // Ação da notificação: relê o estado à Activity e sai (já é foreground).
        val act = intent?.action
        if (act != null && act.startsWith("com.livepoc.")) {
            actionHandler?.invoke(act)
            return START_NOT_STICKY
        }

        mode = intent?.getStringExtra("mode") ?: mode
        micMuted = intent?.getBooleanExtra("micMuted", micMuted) ?: micMuted
        soundMuted = intent?.getBooleanExtra("soundMuted", soundMuted) ?: soundMuted
        paused = intent?.getBooleanExtra("paused", paused) ?: paused

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("live", "Live session", NotificationManager.IMPORTANCE_LOW))

        val notif = buildNotification()
        val type = when (mode) {
            "broadcast" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            // partilha de ecrã: mediaProjection é OBRIGATÓRIO estar ativo ANTES
            // de obter o MediaProjection (Android 14+)
            "screen" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            // chamada: câmara+micro (emissão) e playback (áudio do par)
            "call" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            // chamada com partilha de ecrã (troca de fonte em pleno live)
            "call-screen" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, notif, type)
        else startForeground(1, notif)

        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "livepoc:session")
                .also { it.acquire() }
        }
        return START_NOT_STICKY
    }

    private fun actionPi(action: String, req: Int): PendingIntent {
        val i = Intent(this, LiveService::class.java).setAction(action)
        return PendingIntent.getService(this, req, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(icon: Int, title: String, act: String, req: Int) =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), title, actionPi(act, req)).build()

    private fun buildNotification(): Notification {
        val isCall = mode == "call" || mode == "call-screen"
        val isBroadcast = mode == "broadcast" || mode == "screen"
        val title = when {
            isCall -> "In a call"
            isBroadcast -> if (mode == "screen") "Sharing your screen" else "Broadcasting live"
            else -> "Watching live stream"
        }
        val openIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val contentPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val b = Notification.Builder(this, "live")
            .setSmallIcon(if (mode == "watch") R.drawable.ic_play else R.drawable.ic_live)
            .setContentTitle(title)
            .setContentText("Tap to return to PomboTV")
            .setContentIntent(contentPi)
            .setOngoing(true)

        val compact: IntArray
        if (isBroadcast || isCall) {
            // emissão: mute do MICRO + End
            b.addAction(action(
                if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                if (micMuted) "Unmute mic" else "Mute mic", ACT_MUTE_MIC, 11))
            b.addAction(action(R.drawable.ic_live, "End", ACT_STOP, 14))
            compact = intArrayOf(0, 1)
        } else {
            // watch: mute do SOM + play/pause + Leave
            b.addAction(action(
                if (soundMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                if (soundMuted) "Unmute" else "Mute", ACT_MUTE_SOUND, 12))
            b.addAction(action(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (paused) "Resume" else "Pause", ACT_PAUSE, 13))
            b.addAction(action(R.drawable.ic_play, "Leave", ACT_STOP, 14))
            compact = intArrayOf(0, 1, 2)
        }
        // MediaStyle: os botões aparecem SEMPRE na vista compacta (mesmo colapsada),
        // ao contrário das ações normais que o One UI esconde nas notificações FGS.
        b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(*compact))
        return b.build()
    }

    override fun onDestroy() {
        instance = null
        try { wake?.release() } catch (e: Exception) {}
        wake = null
        super.onDestroy()
    }
}
