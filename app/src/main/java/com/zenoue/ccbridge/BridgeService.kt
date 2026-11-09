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
 * Dịch vụ nền của cầu nối. Hai kênh nhận lệnh (đều phải ký HMAC bằng mật khẩu):
 *  - Đường hầm: HTTP 127.0.0.1:<port> + đường hầm SSH ra URL https công khai (mặc định).
 *  - Supabase: hỏi vòng bảng mailbox (tuỳ chọn).
 */
class BridgeService : Service() {

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 11
        private const val CHUNK = 60_000
        private const val POLL_MS = 1500L
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
        // giữ CPU khi tắt màn hình để đường hầm/hỏi vòng không bị ngủ
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
        if (!cfg.ready) { setStatus("Thiếu cấu hình"); stopSelf(); return }
        tryConnectAdb(cfg)
        if (cfg.tunnelEnabled) startTunnel(cfg)
        val sb = if (cfg.supabaseEnabled && cfg.supabaseReady) cfg.bridge() else null
        if (sb != null) {
            sb.check()?.let { Console.err("Supabase: $it") } ?: Console.info("Supabase đã bật – chờ lệnh từ Claude Code")
        }
        var lastFlush = 0L
        var logCursor = LogStream.lastId()
        while (alive) {
            try {
                if (!Adb.isConnected(this)) tryConnectAdb(cfg)
                if (sb != null) {
                    for (c in sb.pollCommands(cfg.lastSeq)) handleSupabase(cfg, sb, c)
                    val now = System.currentTimeMillis()
                    if (LogStream.running && now - lastFlush > 1200) {
                        val (next, lines) = LogStream.since(logCursor)
                        logCursor = next
                        if (lines.isNotEmpty()) flushLog(sb, lines)
                        lastFlush = now
                    }
                }
                refreshStatus(cfg, sb != null)
            } catch (t: Throwable) {
                Console.err("Lỗi vòng lặp: ${t.message}")
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun refreshStatus(cfg: Config, supa: Boolean) {
        val adb = if (Adb.isConnected(this)) "ADB ✓" else "ADB chưa kết nối"
        val parts = ArrayList<String>()
        if (cfg.tunnelEnabled) parts.add(if (tunnelUrl != null) "Đường hầm ✓" else "Đường hầm…")
        if (supa) parts.add("Supabase ✓")
        setStatus((parts + adb).joinToString(" · "))
    }

    // ───────── đường hầm ─────────

    private fun startTunnel(cfg: Config) {
        val api = HttpApi({ cfg.password }, { type, cmd, t -> Commands.run(this, cfg, type, cmd, t, "đường hầm") })
        try {
            server = LocalServer(cfg.httpPort, api::handle).also { it.start() }
        } catch (t: Throwable) {
            Console.err("Không mở được cổng ${cfg.httpPort}: ${t.message}"); return
        }
        val p = Tunnel.Provider.of(cfg.provider)
        tunnel = Tunnel(p, cfg.httpPort, cfg.sshKey) { st, url ->
            val changed = url != tunnelUrl
            tunnelState = st; tunnelUrl = url
            if (url != null && changed) Console.info("Đường hầm: $url")
            else if (url == null && st.startsWith("Lỗi")) Console.err(st)
            onStatus?.invoke()
        }.also { it.start() }
    }

    // ───────── Supabase ─────────

    private fun handleSupabase(cfg: Config, sb: SupabaseBridge, c: SupabaseBridge.Cmd) {
        if (c.seq <= cfg.lastSeq) return
        cfg.lastSeq = c.seq
        if (!Crypto.verify(cfg.password, c.seq, c.ts, c.type, c.cmd, c.nonce, c.sig)) {
            sb.insertResult(c.seq, 1, 1, -2, "Chữ ký không khớp – sai mật khẩu.")
            Console.err("#${c.seq} từ chối: sai mật khẩu"); return
        }
        if (c.ts in 1..(System.currentTimeMillis() - 600_000)) { Console.err("#${c.seq} bỏ: quá cũ"); return }
        val r = Commands.run(this, cfg, c.type, c.cmd, 300_000L, "Supabase")
        val chunks = if (r.output.isEmpty()) listOf("") else r.output.chunked(CHUNK)
        for ((i, part) in chunks.withIndex()) {
            if (!sb.insertResult(c.seq, i + 1, chunks.size, r.exit, part)) Console.err("Ghi kết quả #${c.seq} phần ${i + 1} lỗi")
        }
    }

    private fun flushLog(sb: SupabaseBridge, lines: List<String>) {
        var buf = StringBuilder()
        for (l in lines) {
            if (buf.length + l.length + 1 > CHUNK) { sb.insertLog(buf.toString()); buf = StringBuilder() }
            buf.append(l).append('\n')
        }
        if (buf.isNotEmpty()) sb.insertLog(buf.toString())
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
