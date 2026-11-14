package com.zenoue.ccbridge.feature

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbStream

/**
 * Handles (already signature-checked) commands sent by Claude Code through the tunnel.
 * Commands and results are mirrored to the CLI tab; [Console] lines are UI text (Vietnamese),
 * replies go back to Claude Code (English).
 */
object Commands {

    fun run(ctx: Context, cfg: Config, type: String, cmd: String, timeoutMs: Long): HttpApi.Reply {
        return when (type) {
            "ping" -> {
                Console.info("claude ping")
                val adb = if (!Adb.isConnected(ctx)) "false" else if (Adb.viaTcp) "true (tcp ${Adb.TCP_PORT})" else "true (wireless tls)"
                HttpApi.Reply(0, "pong | adb=$adb | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | read-only=${!cfg.allowWrite}" +
                    (if (Host.ready) " | feature #${Host.api.featureVersion} (${Host.api.featureSource}) | apk ${Host.api.apkVersion}" else ""))
            }
            "logcat_start" -> {
                val lc = cmd.ifBlank { "logcat -v time" }
                Console.cmdRemote("$lc  (stream)")
                when {
                    !cfg.allowWrite && !Policy.allowed(lc) -> blocked()
                    !Adb.isConnected(ctx) -> noAdb()
                    else -> { LogStream.start(ctx, lc); HttpApi.Reply(0, "Stream started: $lc", LogStream.lastId()) }
                }
            }
            "logcat_stop" -> { LogStream.stop(); Console.info(L.t("claude tắt logcat", "claude stopped logcat")); HttpApi.Reply(0, "Stream stopped.") }
            "logs" -> {
                val (next, lines) = LogStream.since(cmd.trim().toLongOrNull() ?: 0L)
                HttpApi.Reply(if (LogStream.running) 0 else 1, lines.joinToString("") { it + "\n" }, next)
            }
            "exec" -> exec(ctx, cfg, cmd, timeoutMs)
            "cdp" -> cdp(ctx, cfg, cmd, timeoutMs)
            // the app's own console (CLI tab) - tunnel/ADB events with timestamps, for remote diagnosis
            "console" -> HttpApi.Reply(0, Console.dump())
            else -> HttpApi.Reply(-1, "Unsupported command type: $type")
        }
    }

    private fun exec(ctx: Context, cfg: Config, cmd: String, timeoutMs: Long): HttpApi.Reply {
        if (cmd.isBlank()) return HttpApi.Reply(-1, "Empty command.")
        Console.cmdRemote(cmd)
        if (!cfg.allowWrite && !Policy.allowed(cmd)) return blocked()
        if (!Adb.isConnected(ctx)) return noAdb()
        val r = try { Adb.run(ctx, cmd, timeoutMs) } catch (t: Throwable) { Adb.onCommandError(ctx, t) }
        Console.out(r.output.ifBlank { L.t("(không có output)", "(no output)") })
        if (r.exit != 0) Console.err("exit ${r.exit}")
        return HttpApi.Reply(r.exit, r.output)
    }

    /**
     * Chrome DevTools on the phone. [cmd] is JSON: {"op":"sockets"} | {"op":"http","method","path"} |
     * {"op":"call","path":"/devtools/page/<id>","cdp":"Domain.method","params":{}} | {"op":"read","offset"}
     * plus an optional "socket". Listing tabs is a read; everything else needs "allow all commands".
     */
    private fun cdp(ctx: Context, cfg: Config, cmd: String, timeoutMs: Long): HttpApi.Reply {
        val o = try { org.json.JSONObject(cmd) } catch (_: Exception) { return HttpApi.Reply(-1, "Invalid cdp request (JSON expected).") }
        val op = o.optString("op")
        if (op == "read") return Cdp.chunk(o.optLong("offset", -1))
        val socket = o.optString("socket").ifEmpty { Cdp.DEFAULT_SOCKET }
        val path = o.optString("path")
        val method = o.optString("method", "GET").uppercase()
        val call = o.optString("cdp")
        val valid = Cdp.validSocket(socket) && when (op) {
            "sockets" -> true
            "http" -> method in setOf("GET", "PUT") && Cdp.validHttpPath(path)
            "call" -> Cdp.validWsPath(path) && Cdp.validMethod(call)
            else -> false
        }
        if (!valid) return HttpApi.Reply(-1, "Invalid cdp request: op=$op path=$path")
        Console.cmdRemote(when (op) {
            "sockets" -> "chrome sockets"
            "http" -> "chrome $method ${path.take(200)}"
            else -> "chrome $call (${path.substringAfterLast('/').take(8)})"
        })
        if (!cfg.allowWrite && !Cdp.readOnly(op, method, path)) return blocked()
        if (!Adb.isConnected(ctx)) return noAdb()
        return try {
            val text = when (op) {
                "sockets" -> Cdp.sockets(ctx)
                "http" -> Cdp.http(ctx, socket, method, path, timeoutMs)
                else -> Cdp.call(ctx, socket, path, call, o.optJSONObject("params") ?: org.json.JSONObject(), timeoutMs)
            }
            Console.out(if (text.length > 2000) "(${text.length / 1024} KB)" else text.ifBlank { L.t("(không có output)", "(no output)") })
            Cdp.first(text)
        } catch (t: Throwable) {
            Console.err("Chrome DevTools: ${t.message ?: t}")
            HttpApi.Reply(1, "Chrome DevTools ($socket): ${t.message ?: t}")
        }
    }

    private fun blocked(): HttpApi.Reply {
        Console.err(L.t("bị chặn (chỉ-đọc)", "blocked (read-only)"))
        return HttpApi.Reply(HttpApi.EXIT_BLOCKED, "Command blocked in read-only mode (turn on \"Allow all commands\" / \"Cho phép mọi lệnh\" in the app).")
    }

    private fun noAdb(): HttpApi.Reply {
        Console.err(L.t("ADB chưa kết nối", "ADB not connected"))
        return HttpApi.Reply(HttpApi.EXIT_NO_ADB, "ADB is not connected on the phone.")
    }
}

/** Background logcat stream; lines get increasing ids so the client can resume where it left off. */
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
            val pending = java.io.ByteArrayOutputStream()
            try {
                Adb.pump(st, stop = { stream !== st }) { b, n ->
                    for (i in 0 until n) {
                        val c = b[i]
                        if (c == '\n'.code.toByte()) {
                            val line = pending.toString("UTF-8").removeSuffix("\r")
                            pending.reset()
                            synchronized(buf) {
                                buf.addLast(nextId++ to line)
                                while (buf.size > MAX) buf.removeFirst()
                            }
                        } else pending.write(c.toInt())
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

    /** Lines with id > [after] (at most [max]) plus the last id, to continue from next time. */
    fun since(after: Long, max: Int = 2000): Pair<Long, List<String>> = synchronized(buf) {
        // after is beyond the newest id (app restarted) -> read from the start
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

/** Allowlist of safe (read-only) commands used while "allow all commands" is off. */
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
