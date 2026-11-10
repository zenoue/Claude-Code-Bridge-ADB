package com.zenoue.ccbridge.feature

import org.json.JSONObject

/**
 * HTTP protocol served through the tunnel.
 *
 *  GET  /  or /health -> "CC Bridge ADB"  (tunnel liveness, reveals nothing)
 *  POST /cmd          -> JSON body {seq, ts, type, cmd, nonce, sig, timeout?}
 *                        returns  {seq, exit, output, next?}
 *
 * Every command is HMAC-signed with the password, so the password itself never crosses the tunnel.
 * Replay protection: ts may differ by at most [MAX_SKEW_MS] and each nonce is accepted once.
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
                LocalServer.Response(200, HEALTH_BODY + "\n", "text/plain; charset=utf-8")
            r.method == "POST" && path == "/cmd" -> cmd(r.body)
            else -> LocalServer.Response(404, LocalServer.err("not found"))
        }
    }

    private fun cmd(body: String): LocalServer.Response {
        val o = try { JSONObject(body) } catch (_: Exception) {
            return LocalServer.Response(400, LocalServer.err("invalid JSON"))
        }
        val seq = o.optLong("seq"); val ts = o.optLong("ts")
        val type = o.optString("type", "exec"); val c = o.optString("cmd", "")
        val nonce = o.optString("nonce", ""); val sig = o.optString("sig", "")
        val pw = password()
        if (pw.isEmpty() || nonce.length < 8 || !Crypto.verify(pw, seq, ts, type, c, nonce, sig)) {
            return reply(401, seq, Reply(EXIT_BAD_SIGNATURE, "Signature mismatch - wrong password."))
        }
        val t = now()
        if (kotlin.math.abs(t - ts) > MAX_SKEW_MS) {
            return reply(401, seq, Reply(EXIT_CLOCK_SKEW, "Request too old or clock skew (>${MAX_SKEW_MS / 1000}s)."))
        }
        synchronized(seen) {
            val it = seen.entries.iterator()
            while (it.hasNext()) if (t - it.next().value > 2 * MAX_SKEW_MS) it.remove()
            if (seen.containsKey(nonce)) {
                return reply(409, seq, Reply(EXIT_REPLAY, "Request was already received (nonce reused); its result is not available."))
            }
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
        const val HEALTH_BODY = "CC Bridge ADB"
        const val MAX_SKEW_MS = 5 * 60_000L
        const val EXIT_BAD_SIGNATURE = -2
        const val EXIT_BLOCKED = -3
        const val EXIT_NO_ADB = -4
        const val EXIT_CLOCK_SKEW = -5
        const val EXIT_REPLAY = -6
    }
}
