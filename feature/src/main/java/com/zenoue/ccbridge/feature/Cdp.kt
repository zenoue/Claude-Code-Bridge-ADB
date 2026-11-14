package com.zenoue.ccbridge.feature

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Wire formats used to talk to a DevTools endpoint: plain HTTP/1.1 requests and WebSocket frames
 * (RFC 6455, client side: every frame is masked). Pure code, no Android classes.
 */
object Wire {
    const val TEXT = 1
    const val CLOSE = 8
    const val PING = 9
    const val PONG = 10

    class Head(val status: Int, val headers: Map<String, String>)
    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray, val size: Int)

    fun request(method: String, path: String, extraHeaders: String = ""): ByteArray =
        "$method $path HTTP/1.1\r\nHost: localhost\r\n$extraHeaders\r\n".toByteArray(Charsets.ISO_8859_1)

    fun upgrade(path: String, key: String): ByteArray = request(
        "GET", path,
        "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n"
    )

    /** Index just past the blank line that ends an HTTP head, or -1 while it is incomplete. */
    fun headEnd(b: ByteArray, len: Int): Int {
        var i = 0
        while (i + 3 < len) {
            if (b[i] == CR && b[i + 1] == LF && b[i + 2] == CR && b[i + 3] == LF) return i + 4
            i++
        }
        return -1
    }

    fun parseHead(text: String): Head {
        val lines = text.split("\r\n")
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val c = l.indexOf(':')
            if (c > 0) headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
        }
        return Head(status, headers)
    }

    /** One final frame; [mask] has 4 bytes. */
    fun frame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(payload.size + 14)
        o.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> o.write(0x80 or n)
            n < 65536 -> { o.write(0x80 or 126); o.write(n ushr 8); o.write(n and 0xFF) }
            else -> { o.write(0x80 or 127); for (s in 56 downTo 0 step 8) o.write(((n.toLong() ushr s) and 0xFF).toInt()) }
        }
        o.write(mask, 0, 4)
        for (i in 0 until n) o.write(payload[i].toInt() xor mask[i % 4].toInt())
        return o.toByteArray()
    }

    /** Decode the frame at the start of [b] ([len] valid bytes); null while it is incomplete. */
    fun decode(b: ByteArray, len: Int): Frame? {
        if (len < 2) return null
        val fin = (b[0].toInt() and 0x80) != 0
        val opcode = b[0].toInt() and 0x0F
        val masked = (b[1].toInt() and 0x80) != 0
        var n = (b[1].toInt() and 0x7F).toLong()
        var p = 2
        if (n == 126L) {
            if (len < 4) return null
            n = (((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)).toLong(); p = 4
        } else if (n == 127L) {
            if (len < 10) return null
            n = 0
            for (i in 2 until 10) n = (n shl 8) or (b[i].toInt() and 0xFF).toLong()
            p = 10
        }
        if (n < 0 || n > MAX_MESSAGE) throw IOException("WebSocket frame too large ($n bytes)")
        val maskAt = p
        if (masked) p += 4
        if (len < p + n) return null
        val payload = b.copyOfRange(p, p + n.toInt())
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor b[maskAt + i % 4].toInt()).toByte()
        return Frame(fin, opcode, payload, p + n.toInt())
    }

    const val MAX_MESSAGE = 64L * 1024 * 1024
    private const val CR: Byte = 13
    private const val LF: Byte = 10
}

/** Growable byte buffer that can drop bytes from the front. */
class ByteQueue {
    var data = ByteArray(16 * 1024); private set
    var size = 0; private set

    fun add(b: ByteArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(b, 0, data, size, n)
        size += n
    }

    fun drop(n: Int) {
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
    }
}

/**
 * Chrome DevTools Protocol for browsers and WebViews on the phone. Chrome (and other Chromium apps)
 * expose DevTools on an abstract Unix socket such as `chrome_devtools_remote` once USB debugging is on;
 * adbd reaches it as `localabstract:<socket>`, which is what `chrome://inspect` on a PC does too.
 *
 *  - [http]: the JSON endpoints (/json/list, /json/version, /json/new?url, /json/close/<id>, ...)
 *  - [call]: one protocol command over WebSocket (e.g. Runtime.evaluate) and its reply
 *
 * Replies can be large (screenshots), and the free tunnel cuts big responses, so they are handed out
 * in chunks: see [first] and [chunk].
 */
object Cdp {
    const val DEFAULT_SOCKET = "chrome_devtools_remote"
    const val CHUNK = 48_000
    /** tools/bridge.py looks for this text to bring the browser to the front and retry. */
    const val NO_ANSWER = "no answer from the browser (it is probably in the background; open it on the phone)"

    private val SOCKET = Regex("[A-Za-z0-9_.:-]{1,120}")
    private val HTTP_PATH = Regex("/json(/[a-z]+(/[A-Za-z0-9-]+)?)?(\\?[\\x21-\\x7E]*)?")
    // Chrome on Android serves the browser target at plain /devtools/browser (no id)
    private val WS_PATH = Regex("/devtools/(page/[A-Za-z0-9-]+|browser(/[A-Za-z0-9-]+)?)")
    private val METHOD = Regex("[A-Za-z]+\\.[A-Za-z]+")
    /** Endpoints that only read; everything else needs "allow all commands". */
    private val READ_ONLY_PATHS = setOf("/json", "/json/list", "/json/version")

    fun validSocket(s: String) = SOCKET.matches(s)
    fun validHttpPath(s: String) = HTTP_PATH.matches(s)
    fun validWsPath(s: String) = WS_PATH.matches(s)
    fun validMethod(s: String) = METHOD.matches(s)
    fun readOnly(op: String, httpMethod: String, path: String) =
        op == "sockets" || (op == "http" && httpMethod == "GET" && path in READ_ONLY_PATHS)

    /** DevTools sockets currently open on the phone, one per line. */
    fun sockets(ctx: Context): String =
        Adb.run(ctx, "cat /proc/net/unix | grep -a devtools_remote | sed 's/.*@//' | sort -u", 10_000).output.trim()

    fun http(ctx: Context, socket: String, method: String, path: String, timeoutMs: Long): String {
        val st = Adb.open(ctx, "localabstract:$socket")
        try {
            send(st, Wire.request(method, path))
            val q = ByteQueue()
            var head: Wire.Head? = null
            var bodyAt = -1
            var need = -1
            var done = false
            val eof = Adb.pump(st, System.currentTimeMillis() + timeoutMs, stop = { done }) { b, n ->
                q.add(b, n)
                if (head == null) {
                    bodyAt = Wire.headEnd(q.data, q.size)
                    if (bodyAt >= 0) {
                        val h = Wire.parseHead(String(q.data, 0, bodyAt, Charsets.ISO_8859_1))
                        head = h
                        need = h.headers["content-length"]?.toIntOrNull() ?: -1
                    }
                }
                if (head != null && need >= 0 && q.size - bodyAt >= need) done = true
            }
            // a browser in the background is frozen by Android: the socket accepts, nothing answers
            val h = head ?: throw IOException(if (eof) "connection closed by $socket" else NO_ANSWER)
            if (!done && !eof) throw IOException("timed out reading $path")
            val len = if (need >= 0) minOf(need, q.size - bodyAt) else q.size - bodyAt
            val body = String(q.data, bodyAt, len, Charsets.UTF_8)
            if (h.status !in 200..299) throw IOException("HTTP ${h.status} for $path: ${body.take(200)}")
            return body
        } finally {
            try { st.close() } catch (_: Throwable) { }
        }
    }

    /** Send one protocol command to the target behind [wsPath]; returns the reply message (JSON text). */
    fun call(ctx: Context, socket: String, wsPath: String, method: String, params: JSONObject, timeoutMs: Long): String {
        val st = Adb.open(ctx, "localabstract:$socket")
        try {
            val rnd = java.security.SecureRandom()
            val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
            val command = JSONObject().put("id", 1).put("method", method).put("params", params)
                .toString().toByteArray(Charsets.UTF_8)
            fun frame(opcode: Int, payload: ByteArray) =
                send(st, Wire.frame(opcode, payload, ByteArray(4).also { rnd.nextBytes(it) }))

            send(st, Wire.upgrade(wsPath, key))
            val q = ByteQueue()
            val message = ByteArrayOutputStream()
            var upgraded = false
            var result: String? = null
            var error: String? = null
            val eof = Adb.pump(st, System.currentTimeMillis() + timeoutMs, stop = { result != null || error != null }) { b, n ->
                q.add(b, n)
                if (!upgraded) {
                    val end = Wire.headEnd(q.data, q.size)
                    if (end >= 0) {
                        val h = Wire.parseHead(String(q.data, 0, end, Charsets.ISO_8859_1))
                        q.drop(end)
                        if (h.status == 101) { upgraded = true; frame(Wire.TEXT, command) }
                        else error = "WebSocket upgrade refused (HTTP ${h.status})"
                    }
                }
                while (upgraded && result == null && error == null) {
                    val f = Wire.decode(q.data, q.size) ?: break
                    q.drop(f.size)
                    when (f.opcode) {
                        Wire.PING -> frame(Wire.PONG, f.payload)
                        Wire.PONG -> { }
                        Wire.CLOSE -> error = "the browser closed the DevTools connection"
                        else -> {
                            message.write(f.payload, 0, f.payload.size)
                            if (message.size() > Wire.MAX_MESSAGE) error = "DevTools message too large"
                            if (f.fin) {
                                val text = message.toString("UTF-8")
                                message.reset()
                                if (isReply(text)) result = text // anything else is an event
                            }
                        }
                    }
                }
            }
            error?.let { throw IOException(it) }
            return result ?: throw IOException(when {
                eof -> "DevTools connection closed before the reply"
                !upgraded -> NO_ANSWER
                else -> "timed out waiting for $method"
            })
        } finally {
            try { st.close() } catch (_: Throwable) { }
        }
    }

    private fun isReply(text: String): Boolean =
        try { JSONObject(text).optInt("id", -1) == 1 } catch (_: Exception) { false }

    private fun send(st: AdbStream, bytes: ByteArray) {
        st.write(bytes, 0, bytes.size)
        st.flush()
    }

    // ───────── chunked replies ─────────

    @Volatile private var pending = ""

    /** First part of [text]; `next` is the offset to ask for with [chunk] when there is more. */
    fun first(text: String): HttpApi.Reply {
        pending = if (text.length > CHUNK) text else ""
        return part(text, 0)
    }

    /** Next part of the last large reply, starting at [offset]. */
    fun chunk(offset: Long): HttpApi.Reply {
        val text = pending
        if (offset < 0 || offset >= text.length) return HttpApi.Reply(1, "No reply data at offset $offset.")
        return part(text, offset.toInt())
    }

    private fun part(text: String, from: Int): HttpApi.Reply {
        var to = minOf(text.length, from + CHUNK)
        // never split a surrogate pair: each part must be valid text on its own
        if (to < text.length && Character.isHighSurrogate(text[to - 1])) to--
        return HttpApi.Reply(0, text.substring(from, to), if (to < text.length) to.toLong() else null)
    }
}
