package com.zenoue.ccbridge

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Đường hầm SSH ngược chạy ngay trong app (JSch), không cần tài khoản.
 *
 * So với bản cũ:
 *  - Nhiều nhà cung cấp, chế độ "Tự động" tự chuyển sang nhà khác khi một nhà lỗi liên tục.
 *  - localhost.run chạy với `--output json` -> đọc địa chỉ chính xác từ sự kiện JSON (vẫn dự phòng regex).
 *  - Tự kiểm tra sức khoẻ: định kỳ gọi chính địa chỉ công khai; hỏng 2 lần liên tiếp -> nối lại ngay.
 *  - [kick] để nối lại tức thì (vd khi đổi Wi‑Fi/4G) thay vì đợi SSH keep‑alive hết hạn.
 */
class Tunnel(
    private val providers: List<Provider>,
    private val localPort: Int,
    private val keyFile: File,
    /** Đường dẫn (sau địa chỉ gốc) để tự kiểm tra, vd "/<khoá>/health". */
    private val probePath: () -> String,
    private val listener: (State) -> Unit,
) {
    data class Provider(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val user: String,
        /** Cổng xin mở phía server (80 cho localhost.run/serveo, 0 = để server tự chọn). */
        val remotePort: Int,
        /** Lệnh exec gửi kèm (null = mở shell thường). */
        val command: String?,
        /** Tên miền địa chỉ công khai mà nhà cung cấp cấp phát. */
        val urlHosts: List<String>,
    ) {
        companion object {
            val LOCALHOST_RUN = Provider(
                "localhost.run", "localhost.run", "localhost.run", 22, "nokey", 80,
                "--output json", listOf("lhr.life", "lhr.pro", "localhost.run"),
            )
            val PINGGY = Provider(
                "pinggy", "Pinggy", "a.pinggy.io", 443, "ccbridge", 0,
                null, listOf("pinggy.link", "pinggy.online", "pinggy.net"),
            )
            val SERVEO = Provider(
                "serveo", "serveo.net", "serveo.net", 22, "ccbridge", 80,
                null, listOf("serveo.net", "serveousercontent.com"),
            )
            val ALL = listOf(LOCALHOST_RUN, PINGGY, SERVEO)
            const val AUTO = "auto"

            /** Danh sách thử theo cấu hình: một nhà cố định, hoặc tất cả theo thứ tự ưu tiên. */
            fun chain(id: String?): List<Provider> =
                ALL.firstOrNull { it.id == id }?.let { listOf(it) } ?: ALL
        }
    }

    enum class Phase { CONNECTING, ONLINE, RETRYING, STOPPED }

    data class State(val phase: Phase, val provider: Provider?, val url: String?, val message: String)

    private val lock = Object()
    @Volatile private var alive = false
    @Volatile private var kicked = false
    @Volatile private var session: Session? = null
    @Volatile var state = State(Phase.STOPPED, null, null, "Đã dừng"); private set
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
        emit(State(Phase.STOPPED, null, null, "Đã dừng"))
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
        var idx = 0
        var fails = 0
        var backoff = MIN_BACKOFF
        while (alive) {
            val p = providers[idx % providers.size]
            val started = System.currentTimeMillis()
            var gotUrl = false
            var error: String? = null
            kicked = false
            try {
                gotUrl = connectOnce(p)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            }
            dropSession(); session = null
            if (!alive) break

            if (gotUrl) {
                // phiên đã chạy được: lần sau lại ưu tiên nhà đầu danh sách
                idx = 0; fails = 0
                if (System.currentTimeMillis() - started > 60_000) backoff = MIN_BACKOFF
            } else if (++fails >= 2 && providers.size > 1) {
                idx++; fails = 0
            }
            val next = providers[idx % providers.size]

            val wait = if (kicked) 500L else backoff
            val why = error?.let { "Lỗi ${p.label}: $it" } ?: "Mất đường hầm (${p.label})"
            emit(State(Phase.RETRYING, next, null, "$why – thử ${next.label} sau ${wait / 1000}s…"))
            synchronized(lock) {
                if (alive && !kicked) try { lock.wait(wait) } catch (_: InterruptedException) { }
            }
            if (wait == backoff) backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    /** Một phiên SSH. Trả true nếu đã nhận được địa chỉ công khai. */
    private fun connectOnce(p: Provider): Boolean {
        emit(State(Phase.CONNECTING, p, null, "Đang mở đường hầm (${p.label})…"))
        val jsch = JSch()
        ensureKey(jsch)
        jsch.addIdentity(keyFile.absolutePath)
        val s = jsch.getSession(p.user, p.host, p.port)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password,none")
        s.serverAliveInterval = 15_000
        s.serverAliveCountMax = 3
        session = s
        s.connect(20_000)
        s.setPortForwardingR("localhost", p.remotePort, "127.0.0.1", localPort)

        val ch: Channel = if (p.command != null) {
            (s.openChannel("exec") as ChannelExec).apply { setCommand(p.command); setPty(false) }
        } else {
            (s.openChannel("shell") as ChannelShell).apply { setPty(false) }
        }
        val input = ch.inputStream
        ch.connect(15_000)

        var found: String? = null
        val text = StringBuilder()
        val buf = ByteArray(4096)
        while (alive && s.isConnected && !ch.isClosed) {
            val n = try { input.read(buf) } catch (_: Throwable) { -1 }
            if (n < 0) break
            text.append(String(buf, 0, n, Charsets.UTF_8))
            if (text.length > 64 * 1024) text.delete(0, text.length - 8 * 1024)
            val u = TunnelParser.parse(text, p)
            if (u != null && u != found) {
                found = u
                emit(State(Phase.ONLINE, p, u, "Đường hầm sẵn sàng (${p.label})"))
            }
        }
        if (alive && found == null && !kicked) {
            throw IllegalStateException("không nhận được địa chỉ: ${text.takeLast(200).toString().trim().ifEmpty { "(trống)" }}")
        }
        // kênh có thể đóng trong khi chuyển tiếp vẫn chạy: giữ đến khi phiên SSH rớt
        while (alive && s.isConnected) Thread.sleep(1000)
        return found != null
    }

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
        private const val MIN_BACKOFF = 2_000L
        private const val MAX_BACKOFF = 30_000L
        private const val PROBE_EVERY_MS = 45_000L
    }
}

/** Đọc địa chỉ công khai từ output của nhà cung cấp (thuần, có unit test). */
object TunnelParser {
    private val URL_RE = Regex("https://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)")
    // localhost.run --output json: {"event":"tcpip-forward","address":"abc123.lhr.life",...}
    private val JSON_ADDR_RE = Regex("\"address\"\\s*:\\s*\"(?:https?://)?([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)")
    private val SKIP = listOf("admin.", "www.", "docs.", "dashboard.")

    fun parse(text: CharSequence, p: Tunnel.Provider): String? {
        for (m in JSON_ADDR_RE.findAll(text)) host(m.groupValues[1], p)?.let { return it }
        for (m in URL_RE.findAll(text)) host(m.groupValues[1], p)?.let { return it }
        return null
    }

    private fun host(raw: String, p: Tunnel.Provider): String? {
        val h = raw.lowercase()
        val ok = p.urlHosts.any { h.endsWith(".$it") } && SKIP.none { h.startsWith(it) }
        return if (ok) "https://$h" else null
    }
}
