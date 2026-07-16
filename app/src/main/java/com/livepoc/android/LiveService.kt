package com.livepoc.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that keeps a live session (broadcast or watch) alive when
 * the app is backgrounded: without it Android kills camera/microphone access
 * and eventually the process. Started from MainActivity while in foreground
 * (required for camera/mic FGS types); holds a partial wake lock so encode /
 * playback threads keep running with the screen off.
 */
class LiveService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: "watch"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("live", "Live session", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, "live")
            .setSmallIcon(if (mode == "broadcast") R.drawable.ic_live else R.drawable.ic_play)
            .setContentTitle(if (mode == "broadcast") "Broadcasting live" else "Watching live stream")
            .setContentText("PomboTV is running in the background")
            .setOngoing(true)
            .build()
        val type = if (mode == "broadcast")
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, notif, type)
        else startForeground(1, notif)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "livepoc:session")
                .also { it.acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wake?.release() } catch (e: Exception) {}
        wake = null
        super.onDestroy()
    }
}
