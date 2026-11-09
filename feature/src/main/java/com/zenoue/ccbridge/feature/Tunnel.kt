package com.zenoue.ccbridge.feature

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Đường hầm localhost.run chạy ngay trong app (JSch), tương đương
 * `ssh -R 80:localhost:<port> nokey@localhost.run -- --output json`. Không cần tài khoản.
 *
 *  - Mở kênh bằng `connect()` KHÔNG timeout: JSch khi có timeout sẽ gửi want-reply và đứng chờ
 *    localhost.run xác nhận lệnh exec/shell. localhost.run có thể không trả lời (OpenSSH thì không chờ)
 *    -> lỗi "channel request: timeout". Không chờ reply thì đọc thẳng output như OpenSSH.
 *  - Chờ địa chỉ tối đa [URL_WAIT_MS]; không có thì bỏ phiên, lần sau đổi chế độ JSON <-> text.
 *  - Tự kiểm tra định kỳ bằng chính địa chỉ công khai; hỏng 2 lần liên tiếp -> nối lại ngay.
 *  - [kick] để nối lại tức thì (vd khi đổi Wi‑Fi/4G).
 */
class Tunnel(
    private val localPort: Int,
    private val keyFile: File,
    /** Đường dẫn (sau địa chỉ gốc) để tự kiểm tra, vd "/<khoá>/health". */
    private val probePath: () -> String,
    private val host: String = HOST,
    private val port: Int = PORT,
    private val listener: (State) -> Unit,
) {
    enum class Phase { CONNECTING, ONLINE, RETRYING, STOPPED }

    data class State(val phase: Phase, val url: String?, val message: String)

    private val lock = Object()
    @Volatile private var alive = false
    @Volatile private var kicked = false
    @Volatile private var session: Session? = null
    @Volatile var state = State(Phase.STOPPED, null, "Đã dừng"); private set
    private var worker: Thread? = null
    private var prober: Thread? = null

    val url: String? get() = state.url

    fun start() {
        if (alive) return
        alive = true
        worker = Thread { loop() }.apply { isDaemon = true; name = "tunnel"; start() }
        prober = Thread { probeLoop() }.apply { isDaemon = true; name = "tunnel-probe"; start() }
    }

    fun stop() {
        alive = false
        dropSession()
        synchronized(lock) { lock.notifyAll() }
        worker?.interrupt(); prober?.interrupt()
        emit(State(Phase.STOPPED, null, "Đã dừng"))
    }

    /** Bỏ phiên hiện tại và nối lại ngay (không chờ backoff). */
    fun kick() {
        if (!alive) return
        kicked = true
        dropSession()
        synchronized(lock) { lock.notifyAll() }
    }

    private fun emit(s: State) {
        state = s
        try { listener(s) } catch (_: Throwable) { }
    }

    private fun dropSession() {
        try { session?.disconnect() } catch (_: Throwable) { }
    }

    // ───────── vòng nối lại ─────────

    private fun loop() {
        var backoff = MIN_BACKOFF
        var json = true
        while (alive) {
            val started = System.currentTimeMillis()
            var gotUrl = false
            var error: String? = null
            kicked = false
            try {
                gotUrl = connectOnce(json)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            }
            dropSession(); session = null
            if (!alive) break

            if (gotUrl) {
                if (System.currentTimeMillis() - started > 60_000) backoff = MIN_BACKOFF
            } else if (!kicked) {
                json = !json // chế độ này không ra địa chỉ -> lần sau thử chế độ kia
            }
            val wait = if (kicked) 500L else backoff
            val why = error?.let { "Lỗi localhost.run: $it" } ?: "Mất đường hầm localhost.run"
            emit(State(Phase.RETRYING, null, "$why – thử lại sau ${wait / 1000}s…"))
            synchronized(lock) {
                if (alive && !kicked) try { lock.wait(wait) } catch (_: InterruptedException) { }
            }
            if (wait == backoff) backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    /** Một phiên SSH. Trả true nếu đã nhận được địa chỉ công khai. */
    private fun connectOnce(json: Boolean): Boolean {
        emit(State(Phase.CONNECTING, null, "Đang mở đường hầm localhost.run…"))
        val jsch = JSch()
        ensureKey(jsch)
        jsch.addIdentity(keyFile.absolutePath)
        val s = jsch.getSession(USER, host, port)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password,none")
        s.serverAliveInterval = 15_000
        s.serverAliveCountMax = 3
        session = s
        s.connect(20_000)
        // như `-R 80:localhost:<port>`: OpenSSH gửi bind address "localhost" khi không ghi host
        s.setPortForwardingR("localhost", 80, "127.0.0.1", localPort)

        val ch: Channel = if (json) {
            (s.openChannel("exec") as ChannelExec).apply { setCommand(JSON_COMMAND); setPty(false) }
        } else {
            (s.openChannel("shell") as ChannelShell).apply { setPty(false) }
        }
        val input = ch.inputStream
        ch.connect() // không timeout = không chờ want-reply (xem chú thích lớp)

        val found = waitForUrl(s, ch, input)
        emit(State(Phase.ONLINE, found, "Đường hầm sẵn sàng"))
        // đọc tiếp để nhận địa chỉ mới (nếu có) và giữ kênh; kênh đóng mà phiên còn thì vẫn giữ
        var current = found
        val text = StringBuilder()
        val buf = ByteArray(4096)
        while (alive && s.isConnected) {
            if (!ch.isClosed && input.available() > 0) {
                val n = input.read(buf)
                if (n > 0) {
                    text.append(String(buf, 0, n, Charsets.UTF_8))
                    if (text.length > 16 * 1024) text.delete(0, text.length - 4 * 1024)
                    val u = TunnelParser.parse(text)
                    if (u != null && u != current) {
                        current = u
                        emit(State(Phase.ONLINE, u, "Đường hầm sẵn sàng (địa chỉ mới)"))
                    }
                }
            } else {
                Thread.sleep(200)
            }
        }
        return true
    }

    /** Đọc output đến khi thấy địa chỉ; hết [URL_WAIT_MS] hoặc kênh đóng -> ném lỗi kèm output. */
    private fun waitForUrl(s: Session, ch: Channel, input: InputStream): String {
        val deadline = System.currentTimeMillis() + URL_WAIT_MS
        val text = StringBuilder()
        val buf = ByteArray(4096)
        while (alive && s.isConnected) {
            val avail = try { input.available() } catch (_: Throwable) { -1 }
            if (avail > 0) {
                val n = input.read(buf)
                if (n > 0) {
                    text.append(String(buf, 0, n, Charsets.UTF_8))
                    if (text.length > 64 * 1024) text.delete(0, text.length - 8 * 1024)
                    TunnelParser.parse(text)?.let { return it }
                }
                continue
            }
            if (avail < 0 || ch.isClosed) break
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("hết ${URL_WAIT_MS / 1000}s chưa nhận được địa chỉ. Output: ${tail(text)}")
            }
            Thread.sleep(150)
        }
        if (!alive || kicked) throw IllegalStateException("đã dừng")
        throw IllegalStateException(
            (if (!s.isConnected) "phiên SSH bị đóng" else "kênh bị đóng") + " trước khi có địa chỉ. Output: ${tail(text)}"
        )
    }

    private fun tail(t: CharSequence) = t.takeLast(240).toString().replace(Regex("\\s+"), " ").trim().ifEmpty { "(trống)" }

    private fun ensureKey(jsch: JSch) {
        if (keyFile.exists() && keyFile.length() > 0) return
        keyFile.parentFile?.mkdirs()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        kp.writePrivateKey(keyFile.absolutePath)
        kp.dispose()
    }

    // ───────── tự kiểm tra ─────────

    private fun probeLoop() {
        var bad = 0
        while (alive) {
            try { Thread.sleep(PROBE_EVERY_MS) } catch (_: InterruptedException) { break }
            val st = state
            val u = st.url
            if (st.phase != Phase.ONLINE || u == null) { bad = 0; continue }
            if (probe(u + probePath())) bad = 0
            else if (++bad >= 2 && state.url == u) {
                bad = 0
                emit(st.copy(message = "Địa chỉ không phản hồi – nối lại…"))
                kick()
            }
        }
    }

    private fun probe(target: String): Boolean = try {
        val c = URL(target).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "cc-bridge-adb/probe")
        try {
            c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() }.startsWith(HttpApi.HEALTH_BODY)
        } finally { c.disconnect() }
    } catch (_: Throwable) { false }

    companion object {
        const val HOST = "localhost.run"
        const val PORT = 22
        const val USER = "nokey"
        const val JSON_COMMAND = "--output json"
        private const val URL_WAIT_MS = 40_000L
        private const val MIN_BACKOFF = 2_000L
        private const val MAX_BACKOFF = 30_000L
        private const val PROBE_EVERY_MS = 45_000L
    }
}

/** Đọc địa chỉ công khai từ output của localhost.run (thuần, có unit test). */
object TunnelParser {
    private val URL_RE = Regex("https://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)")
    // --output json: {"event":"tcpip-forward","address":"abc123.lhr.life",...}
    private val JSON_ADDR_RE = Regex("\"address\"\\s*:\\s*\"(?:https?://)?([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)")
    private val HOSTS = listOf("lhr.life", "lhr.pro", "localhost.run")
    private val SKIP = listOf("admin.", "www.", "docs.", "dashboard.")

    fun parse(text: CharSequence): String? {
        for (m in JSON_ADDR_RE.findAll(text)) host(m.groupValues[1])?.let { return it }
        for (m in URL_RE.findAll(text)) host(m.groupValues[1])?.let { return it }
        return null
    }

    private fun host(raw: String): String? {
        val h = raw.lowercase()
        val ok = HOSTS.any { h.endsWith(".$it") } && SKIP.none { h.startsWith(it) }
        return if (ok) "https://$h" else null
    }
}
