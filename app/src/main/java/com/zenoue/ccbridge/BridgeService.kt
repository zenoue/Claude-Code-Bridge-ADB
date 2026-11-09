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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections

/**
 * Dịch vụ nền: hỏi vòng Supabase lấy lệnh của Claude, chạy adb, ghi kết quả/log về Supabase.
 * Lệnh và kết quả cũng hiện trong cửa sổ lệnh (Console).
 */
class BridgeService : Service() {

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 11
        private const val CHUNK = 60_000
        private const val POLL_MS = 1500L
        @Volatile var running = false; private set
        @Volatile var status = "Chưa chạy"; private set
        @Volatile var onStatus: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BridgeService::class.java))
    }

    @Volatile private var alive = false
    private var worker: Thread? = null
    @Volatile private var logcatStream: io.github.muntashirakon.adb.AdbStream? = null
    private val logcatBuf = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var logcatDirty = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notif("Đang khởi động…"), type)
        else startForeground(NOTIF_ID, notif("Đang khởi động…"))
        alive = true; running = true
        worker = Thread { loop() }.apply { isDaemon = true; start() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        alive = false; running = false
        stopLogcat()
        worker?.interrupt()
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
        val sb = cfg.bridge()
        sb.check()?.let { setStatus("Lỗi Supabase: $it"); Console.err(it) } ?: Console.info("Cầu nối Supabase đã bật – chờ lệnh từ Claude Code")
        tryConnectAdb(cfg)
        var lastFlush = 0L
        while (alive) {
            try {
                if (!Adb.isConnected(this)) tryConnectAdb(cfg)
                setStatus(if (Adb.isConnected(this)) "Đang nghe lệnh…" else "Chờ (ADB chưa kết nối)")
                val cmds = sb.pollCommands(cfg.lastSeq)
                for (c in cmds) handle(cfg, sb, c)
                val now = System.currentTimeMillis()
                if (logcatDirty && now - lastFlush > 1200) { flushLogcat(sb); lastFlush = now }
            } catch (t: Throwable) {
                Console.err("Lỗi vòng lặp: ${t.message}")
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun handle(cfg: Config, sb: SupabaseBridge, c: SupabaseBridge.Cmd) {
        if (c.seq <= cfg.lastSeq) return
        cfg.lastSeq = c.seq
        if (!Crypto.verify(cfg.password, c.seq, c.ts, c.type, c.cmd, c.nonce, c.sig)) {
            sb.insertResult(c.seq, 1, 1, -2, "Chữ ký không khớp – sai mật khẩu.")
            Console.err("#${c.seq} từ chối: sai mật khẩu"); return
        }
        if (c.ts in 1..(System.currentTimeMillis() - 600_000)) { Console.err("#${c.seq} bỏ: quá cũ"); return }

        when (c.type) {
            "ping" -> { sb.insertResult(c.seq, 1, 1, 0, "pong · ADB=${Adb.isConnected(this)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"); Console.info("claude ping #${c.seq}") }
            "logcat_start" -> {
                val lc = c.cmd.ifBlank { "logcat -v time" }
                if (!cfg.allowWrite && !Policy.allowed(lc)) { sb.insertResult(c.seq, 1, 1, -3, "Lệnh bị chặn (chỉ-đọc)."); return }
                if (!Adb.isConnected(this)) { sb.insertResult(c.seq, 1, 1, -4, "ADB chưa kết nối."); return }
                startLogcat(lc); sb.insertResult(c.seq, 1, 1, 0, "Đã bật stream: $lc"); Console.cmdRemote("$lc  (stream)")
            }
            "logcat_stop" -> { stopLogcat(); sb.insertResult(c.seq, 1, 1, 0, "Đã tắt stream."); Console.info("tắt logcat #${c.seq}") }
            else -> execRemote(cfg, sb, c.seq, c.cmd)
        }
    }

    private fun execRemote(cfg: Config, sb: SupabaseBridge, seq: Long, cmd: String) {
        if (cmd.isBlank()) { sb.insertResult(seq, 1, 1, -1, "Lệnh rỗng."); return }
        if (!cfg.allowWrite && !Policy.allowed(cmd)) {
            sb.insertResult(seq, 1, 1, -3, "Lệnh bị chặn ở chế độ chỉ-đọc.")
            Console.cmdRemote(cmd); Console.err("bị chặn (chỉ-đọc)"); return
        }
        if (!Adb.isConnected(this)) { sb.insertResult(seq, 1, 1, -4, "ADB chưa kết nối."); Console.cmdRemote(cmd); Console.err("ADB chưa kết nối"); return }
        setStatus("Đang chạy #$seq…")
        Console.cmdRemote(cmd)
        val r = try { Adb.run(this, cmd) } catch (t: Throwable) { Adb.disconnect(this); Adb.Result(-1, t.toString(), "ADB") }
        Console.out(r.output.ifBlank { "(không có output)" })
        if (r.exit != 0) Console.err("exit ${r.exit}")
        sendResult(sb, seq, r.exit, r.output)
        setStatus("Đang nghe lệnh…")
    }

    private fun sendResult(sb: SupabaseBridge, seq: Long, exit: Int, output: String) {
        val chunks = if (output.isEmpty()) listOf("") else output.chunked(CHUNK)
        for ((i, c) in chunks.withIndex()) {
            if (!sb.insertResult(seq, i + 1, chunks.size, exit, c)) Console.err("Ghi kết quả #$seq phần ${i + 1} lỗi")
        }
    }

    // ───────── logcat ─────────

    private fun startLogcat(cmd: String) {
        stopLogcat()
        try {
            val st = Adb.openShell(this, cmd)
            logcatStream = st
            Thread {
                try {
                    BufferedReader(InputStreamReader(st.openInputStream())).use { r ->
                        while (alive && logcatStream === st) {
                            val line = r.readLine() ?: break
                            synchronized(logcatBuf) { logcatBuf.add(line); while (logcatBuf.size > 5000) logcatBuf.removeAt(0) }
                            logcatDirty = true
                        }
                    }
                } catch (_: Throwable) { }
            }.apply { isDaemon = true; start() }
        } catch (t: Throwable) { Console.err("Không bật được logcat: ${t.message}") }
    }

    private fun stopLogcat() {
        val st = logcatStream; logcatStream = null
        try { st?.close() } catch (_: Exception) { }
    }

    private fun flushLogcat(sb: SupabaseBridge) {
        logcatDirty = false
        val lines = synchronized(logcatBuf) { val c = ArrayList(logcatBuf); logcatBuf.clear(); c }
        if (lines.isEmpty()) return
        var buf = StringBuilder()
        for (l in lines) {
            if (buf.length + l.length + 1 > CHUNK) { sb.insertLog(buf.toString()); buf = StringBuilder() }
            buf.append(l).append('\n')
        }
        if (buf.isNotEmpty()) { sb.insertLog(buf.toString()); Console.out(buf.toString()) }
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
            .setContentTitle("Claude Code ↔ ADB (Supabase)")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi)
            .build()
    }
}

/** Danh sách lệnh an toàn (chỉ-đọc) khi chưa bật "Cho phép mọi lệnh". */
object Policy {
    private val safe = setOf(
        "dumpsys", "getprop", "logcat", "cmd", "ls", "cat", "pm", "settings",
        "top", "ps", "df", "free", "uptime", "date", "whoami", "id", "wm", "am",
        "service", "stat", "head", "tail", "grep", "awk", "sed", "echo", "printf",
        "getevent", "bugreport", "netstat", "ip", "ifconfig", "find", "wc", "sort", "uniq"
    )
    private val denyWords = listOf(
        ">", "rm ", "reboot", "setprop", "stop ", "start ", "uninstall", "install",
        "clear ", "svc ", "kill", "reset", "wipe", "format", "dd ", "mkfs",
        "settings put", "pm disable", "pm enable", "pm grant", "pm revoke", "am force-stop"
    )

    fun allowed(cmd: String): Boolean {
        val low = cmd.lowercase()
        if (denyWords.any { low.contains(it) }) return false
        return cmd.split("|", ";", "&&").all { seg ->
            val first = seg.trim().split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return@all false
            first.isEmpty() || safe.contains(first)
        }
    }
}

object Fmt {
    fun clock(t: Long = System.currentTimeMillis()): String =
        android.text.format.DateFormat.format("HH:mm:ss", t).toString()
}
