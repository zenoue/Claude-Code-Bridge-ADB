package com.zenoue.ccbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Dịch vụ nền: server HTTP 127.0.0.1:<port> + đường hầm SSH ra URL https công khai.
 * Claude Code gửi lệnh đã ký HMAC vào URL đó; lệnh chạy qua ADB nội bộ.
 */
class BridgeService : Service() {

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 11
        @Volatile var running = false; private set
        @Volatile var status = "Chưa chạy"; private set
        @Volatile var tunnelUrl: String? = null; private set
        @Volatile var tunnelState: String = ""; private set
        @Volatile var onStatus: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BridgeService::class.java))
    }

    @Volatile private var alive = false
    private var worker: Thread? = null
    private var server: LocalServer? = null
    private var tunnel: Tunnel? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notif("Đang khởi động…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, notif("Đang khởi động…"))
        alive = true; running = true
        // giữ CPU khi tắt màn hình để đường hầm không bị ngủ
        wake = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ccbridge:bridge")?.apply { setReferenceCounted(false); acquire() }
        worker = Thread { loop() }.apply { isDaemon = true; name = "bridge"; start() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        alive = false; running = false
        worker?.interrupt()
        tunnel?.stop(); tunnel = null
        server?.stop(); server = null
        LogStream.stop()
        try { wake?.release() } catch (_: Throwable) { }
        tunnelUrl = null; tunnelState = ""
        status = "Đã dừng"; onStatus?.invoke()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        Console.info("Cầu nối đã dừng")
        super.onDestroy()
    }

    private fun tryConnectAdb(cfg: Config) {
        if (Adb.isConnected(this)) return
        if (!cfg.paired || cfg.adbPort <= 0) return
        if (Adb.connect(this, cfg.adbHost, cfg.adbPort)) Console.info("ADB kết nối ${cfg.adbHost}:${cfg.adbPort}")
    }

    private fun loop() {
        val cfg = Config(this)
        if (!cfg.ready) { setStatus("Thiếu mật khẩu"); stopSelf(); return }
        tryConnectAdb(cfg)
        if (!startTunnel(cfg)) { stopSelf(); return }
        while (alive) {
            try {
                if (!Adb.isConnected(this)) tryConnectAdb(cfg)
                val adb = if (Adb.isConnected(this)) "ADB ✓" else "ADB chưa kết nối"
                setStatus((if (tunnelUrl != null) "Đường hầm ✓" else "Đang mở đường hầm…") + " · " + adb)
            } catch (t: Throwable) {
                Console.err("Lỗi vòng lặp: ${t.message}")
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    private fun startTunnel(cfg: Config): Boolean {
        val api = HttpApi({ cfg.password }, { type, cmd, t -> Commands.run(this, cfg, type, cmd, t) })
        try {
            server = LocalServer(cfg.httpPort, api::handle).also { it.start() }
        } catch (t: Throwable) {
            Console.err("Không mở được cổng ${cfg.httpPort}: ${t.message}")
            setStatus("Lỗi cổng ${cfg.httpPort}")
            return false
        }
        val p = Tunnel.Provider.of(cfg.provider)
        tunnel = Tunnel(p, cfg.httpPort, cfg.sshKey) { st, url ->
            val changed = url != tunnelUrl
            tunnelState = st; tunnelUrl = url
            if (url != null && changed) Console.info("Đường hầm: $url")
            else if (url == null && st.startsWith("Lỗi")) Console.err(st)
            onStatus?.invoke()
        }.also { it.start() }
        return true
    }

    // ───────── notification ─────────

    private fun setStatus(s: String) {
        if (s == status) return
        status = s; onStatus?.invoke()
        if (alive) getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif(s))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Cầu nối ADB", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Claude Code ↔ ADB")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi)
            .build()
    }
}
