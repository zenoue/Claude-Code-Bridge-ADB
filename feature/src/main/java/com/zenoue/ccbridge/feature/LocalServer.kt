package com.zenoue.ccbridge.feature

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 server bound to 127.0.0.1 only (never exposed to the LAN).
 * The SSH tunnel forwards connections from the Internet to this port.
 */
class LocalServer(private val port: Int, private val handler: (Request) -> Response) {

    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    data class Response(val code: Int, val body: String, val type: String = "application/json; charset=utf-8")

    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        stop()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        server = ss
        val p = Executors.newCachedThreadPool()
        pool = p
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Throwable) { break }
                try { p.execute { serve(s) } } catch (_: Throwable) { try { s.close() } catch (_: Throwable) { } }
            }
        }.apply { isDaemon = true; name = "local-server"; start() }
    }

    fun stop() {
        try { server?.close() } catch (_: Throwable) { }
        server = null
        pool?.shutdownNow(); pool = null
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = BufferedInputStream(sock.getInputStream())
                val reqLine = readLine(input) ?: return
                val parts = reqLine.split(" ")
                if (parts.size < 2) { write(sock, Response(400, err("bad request"))); return }
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len > MAX_BODY) { write(sock, Response(413, err("body too large"))); return }
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(body, off, len - off)
                    if (n < 0) break
                    off += n
                }
                val req = Request(parts[0].uppercase(), parts[1], headers, String(body, 0, off, Charsets.UTF_8))
                val resp = try { handler(req) } catch (t: Throwable) { Response(500, err(t.toString())) }
                write(sock, resp)
            } catch (_: Throwable) { }
        }
    }

    private fun write(s: Socket, r: Response) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${r.code} ${reason(r.code)}\r\n" +
            "Content-Type: ${r.type}\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        const val MAX_BODY = 1 shl 20

        fun err(msg: String) = "{\"error\":" + jsonStr(msg) + "}"

        fun jsonStr(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; else -> "Error"
        }

        /** Read one \n-terminated line (dropping \r), at most 16 KB. */
        private fun readLine(i: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = i.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 16 * 1024) return null
            }
            return b.toString("UTF-8")
        }
    }
}
