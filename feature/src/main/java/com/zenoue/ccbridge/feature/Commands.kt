package com.zenoue.ccbridge.feature

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Xử lý lệnh (đã kiểm chữ ký) Claude Code gửi qua đường hầm.
 * Lệnh và kết quả cũng hiện trong tab CLI.
 */
object Commands {

    fun run(ctx: Context, cfg: Config, type: String, cmd: String, timeoutMs: Long): HttpApi.Reply {
        return when (type) {
            "ping" -> {
                Console.info("claude ping")
                HttpApi.Reply(0, "pong · ADB=${Adb.isConnected(ctx)}${if (Adb.connected) " (" + Adb.mode + ")" else ""} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · chỉ-đọc=${!cfg.allowWrite}" +
                    (if (Host.ready) " · chức năng v1.0.${Host.api.featureVersion} (${Host.api.featureSource})" else ""))
            }
            "logcat_start" -> {
                val lc = cmd.ifBlank { "logcat -v time" }
                Console.cmdRemote("$lc  (stream)")
                when {
                    !cfg.allowWrite && !Policy.allowed(lc) -> blocked()
                    !Adb.isConnected(ctx) -> noAdb()
                    else -> { LogStream.start(ctx, lc); HttpApi.Reply(0, "Đã bật stream: $lc", LogStream.lastId()) }
                }
            }
            "logcat_stop" -> { LogStream.stop(); Console.info("claude tắt logcat"); HttpApi.Reply(0, "Đã tắt stream.") }
            "logs" -> {
                val (next, lines) = LogStream.since(cmd.trim().toLongOrNull() ?: 0L)
                HttpApi.Reply(if (LogStream.running) 0 else 1, lines.joinToString("") { it + "\n" }, next)
            }
            "exec" -> exec(ctx, cfg, cmd, timeoutMs)
            else -> HttpApi.Reply(-1, "Loại lệnh không hỗ trợ: $type")
        }
    }

    private fun exec(ctx: Context, cfg: Config, cmd: String, timeoutMs: Long): HttpApi.Reply {
        if (cmd.isBlank()) return HttpApi.Reply(-1, "Lệnh rỗng.")
        Console.cmdRemote(cmd)
        if (!cfg.allowWrite && !Policy.allowed(cmd)) return blocked()
        if (!Adb.isConnected(ctx)) return noAdb()
        val r = try { Adb.run(ctx, cmd, timeoutMs) } catch (t: Throwable) {
            Adb.disconnect(ctx); Adb.Result(-1, t.toString(), "ADB")
        }
        Console.out(r.output.ifBlank { "(không có output)" })
        if (r.exit != 0) Console.err("exit ${r.exit}")
        return HttpApi.Reply(r.exit, r.output)
    }

    private fun blocked(): HttpApi.Reply {
        Console.err("bị chặn (chỉ-đọc)")
        return HttpApi.Reply(-3, "Lệnh bị chặn ở chế độ chỉ-đọc (bật \"Cho phép mọi lệnh\" trong app).")
    }

    private fun noAdb(): HttpApi.Reply {
        Console.err("ADB chưa kết nối")
        return HttpApi.Reply(-4, "ADB chưa kết nối trên điện thoại.")
    }
}

/** Stream logcat chạy nền; các dòng có id tăng dần để Claude đọc tiếp từ chỗ cũ. */
object LogStream {
    private const val MAX = 5000
    private val buf = ArrayDeque<Pair<Long, String>>()
    private var nextId = 1L
    @Volatile private var stream: AdbStream? = null

    val running: Boolean get() = stream != null

    fun lastId(): Long = synchronized(buf) { nextId - 1 }

    fun start(ctx: Context, cmd: String) {
        stop()
        val st = Adb.openShell(ctx, cmd)
        stream = st
        Thread {
            try {
                BufferedReader(InputStreamReader(st.openInputStream())).use { r ->
                    while (stream === st) {
                        val line = r.readLine() ?: break
                        synchronized(buf) {
                            buf.addLast(nextId++ to line)
                            while (buf.size > MAX) buf.removeFirst()
                        }
                    }
                }
            } catch (_: Throwable) { }
            if (stream === st) stream = null
        }.apply { isDaemon = true; name = "logcat"; start() }
    }

    fun stop() {
        val st = stream; stream = null
        try { st?.close() } catch (_: Throwable) { }
    }

    /** Các dòng có id > [after] (tối đa [max]); trả kèm id cuối để lần sau đọc tiếp. */
    fun since(after: Long, max: Int = 2000): Pair<Long, List<String>> = synchronized(buf) {
        // after vượt quá id hiện có (app vừa khởi động lại) -> đọc từ đầu
        val a = if (after >= nextId) 0L else after
        val out = ArrayList<String>()
        var last = a
        for ((id, l) in buf) {
            if (id <= a) continue
            out.add(l); last = id
            if (out.size >= max) break
        }
        last to out
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
        return cmd.split("|", ";", "&&", "\n").all { seg ->
            val first = seg.trim().split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return@all false
            first.isEmpty() || safe.contains(first)
        }
    }
}

object Fmt {
    fun clock(t: Long = System.currentTimeMillis()): String =
        android.text.format.DateFormat.format("HH:mm:ss", t).toString()
}
