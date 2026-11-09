package com.zenoue.ccbridge

import org.json.JSONObject

/**
 * Giao thức HTTP của đường hầm – không mật khẩu.
 *
 * Địa chỉ dán cho Claude Code có dạng `https://xxxx.lhr.life/<khoá>`; khoá ngẫu nhiên do app tự sinh
 * (không phải nhập/nhớ), nằm sẵn trong địa chỉ. Sai khoá -> 404 như đường dẫn không tồn tại.
 *
 *  GET  /                -> "CC Bridge ADB" (đường hầm còn sống, không lộ gì)
 *  GET  /<khoá>/health   -> "ok"            (app tự kiểm tra đường hầm)
 *  POST /<khoá>/cmd      -> body JSON {type, cmd, timeout?}  trả {exit, output, next?}
 */
class HttpApi(
    private val key: () -> String,
    private val exec: (type: String, cmd: String, timeoutMs: Long) -> Reply,
) {
    data class Reply(val exit: Int, val output: String, val next: Long? = null)

    fun handle(r: LocalServer.Request): LocalServer.Response {
        val path = r.path.substringBefore('?').trimEnd('/')
        if (r.method == "GET" && path.isEmpty()) {
            return LocalServer.Response(200, "CC Bridge ADB\n", TEXT)
        }
        val k = key()
        val seg = path.removePrefix("/").substringBefore('/')
        if (k.isEmpty() || !constEq(seg, k)) return notFound()
        return when (path.removePrefix("/$seg")) {
            "/health" -> if (r.method == "GET") LocalServer.Response(200, HEALTH_BODY + "\n", TEXT) else notFound()
            "/cmd" -> if (r.method == "POST") cmd(r.body) else notFound()
            else -> notFound()
        }
    }

    private fun cmd(body: String): LocalServer.Response {
        val o = try { JSONObject(body) } catch (_: Exception) {
            return LocalServer.Response(400, LocalServer.err("JSON không hợp lệ"))
        }
        val type = o.optString("type", "exec")
        val c = o.optString("cmd", "")
        val timeout = o.optLong("timeout", 60_000L).coerceIn(1_000L, 600_000L)
        val r = exec(type, c, timeout)
        val j = JSONObject().put("exit", r.exit).put("output", r.output)
        if (r.next != null) j.put("next", r.next)
        return LocalServer.Response(200, j.toString())
    }

    private fun notFound() = LocalServer.Response(404, LocalServer.err("not found"))

    companion object {
        const val HEALTH_BODY = "ok"
        private const val TEXT = "text/plain; charset=utf-8"

        /** So sánh không phụ thuộc thời gian (chống dò khoá theo thời gian phản hồi). */
        fun constEq(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var r = 0
            for (i in a.indices) r = r or (a[i].code xor b[i].code)
            return r == 0
        }
    }
}
