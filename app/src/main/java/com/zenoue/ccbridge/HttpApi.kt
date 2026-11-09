package com.zenoue.ccbridge

import org.json.JSONObject

/**
 * Giao thức HTTP cho chế độ đường hầm.
 *
 *  GET  /       -> "CC Bridge ADB" (kiểm tra đường hầm, không lộ thông tin)
 *  POST /cmd    -> body JSON {seq, ts, type, cmd, nonce, sig, timeout?}
 *                  trả {seq, exit, output, next?}
 *
 * Mỗi lệnh phải ký HMAC bằng mật khẩu, nên mật khẩu không bao giờ đi qua đường hầm.
 * Chống phát lại: ts lệch tối đa [MAX_SKEW_MS] và mỗi nonce chỉ dùng một lần.
 */
class HttpApi(
    private val password: () -> String,
    private val exec: (type: String, cmd: String, timeoutMs: Long) -> Reply,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Reply(val exit: Int, val output: String, val next: Long? = null)

    private val seen = LinkedHashMap<String, Long>()

    fun handle(r: LocalServer.Request): LocalServer.Response {
        val path = r.path.substringBefore('?')
        return when {
            r.method == "GET" && (path == "/" || path == "/health") ->
                LocalServer.Response(200, "CC Bridge ADB\n", "text/plain; charset=utf-8")
            r.method == "POST" && path == "/cmd" -> cmd(r.body)
            else -> LocalServer.Response(404, LocalServer.err("not found"))
        }
    }

    private fun cmd(body: String): LocalServer.Response {
        val o = try { JSONObject(body) } catch (_: Exception) {
            return LocalServer.Response(400, LocalServer.err("JSON không hợp lệ"))
        }
        val seq = o.optLong("seq"); val ts = o.optLong("ts")
        val type = o.optString("type", "exec"); val c = o.optString("cmd", "")
        val nonce = o.optString("nonce", ""); val sig = o.optString("sig", "")
        val pw = password()
        if (pw.isEmpty() || nonce.length < 8 || !Crypto.verify(pw, seq, ts, type, c, nonce, sig)) {
            return reply(401, seq, Reply(-2, "Chữ ký không khớp – sai mật khẩu."))
        }
        val t = now()
        if (kotlin.math.abs(t - ts) > MAX_SKEW_MS) {
            return reply(401, seq, Reply(-5, "Lệnh quá cũ hoặc đồng hồ lệch (>${MAX_SKEW_MS / 1000}s)."))
        }
        synchronized(seen) {
            val it = seen.entries.iterator()
            while (it.hasNext()) if (t - it.next().value > 2 * MAX_SKEW_MS) it.remove()
            if (seen.containsKey(nonce)) return reply(409, seq, Reply(-6, "Lệnh bị gửi lại (nonce đã dùng)."))
            seen[nonce] = t
        }
        val timeout = o.optLong("timeout", 60_000L).coerceIn(1_000L, 600_000L)
        return reply(200, seq, exec(type, c, timeout))
    }

    private fun reply(code: Int, seq: Long, r: Reply): LocalServer.Response {
        val j = JSONObject().put("seq", seq).put("exit", r.exit).put("output", r.output)
        if (r.next != null) j.put("next", r.next)
        return LocalServer.Response(code, j.toString())
    }

    companion object {
        const val MAX_SKEW_MS = 5 * 60_000L
    }
}
