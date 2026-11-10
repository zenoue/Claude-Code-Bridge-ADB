package com.zenoue.ccbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.zenoue.ccbridge.api.ServiceDelegate

/** Service shell: forwards the lifecycle to the feature (BridgeCore inside feature.dex). */
class BridgeService : Service() {

    private var d: ServiceDelegate? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            d = FeatureHost.entry().service(this).also { it.onCreate() }
        } catch (t: Throwable) {
            FeatureHost.onFeatureFailure(t)
            d = null
            // startForegroundService requires a startForeground call, even when about to stop
            foregroundThenStop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        try { d?.onStartCommand(intent, flags, startId) ?: START_NOT_STICKY } catch (t: Throwable) {
            FeatureHost.onFeatureFailure(t); stopSelf(); START_NOT_STICKY
        }

    override fun onDestroy() {
        try { d?.onDestroy() } catch (t: Throwable) { FeatureHost.onFeatureFailure(t) }
        super.onDestroy()
    }

    private fun foregroundThenStop() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel("bridge", "Cầu nối ADB", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bridge").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Claude Code ↔ ADB").setContentText("Chức năng lỗi – mở app để xử lý").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(11, n)
        stopSelf()
    }
}
