package com.zenoue.ccbridge.feature

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.File
import java.io.InputStream

/**
 * localhost.run tunnel running inside the app (JSch), equivalent to
 * `ssh -R 80:localhost:<port> nokey@localhost.run -- --output json`. No account needed.
 *
 *  - The channel is opened with `connect()` WITHOUT a timeout: with a timeout JSch sets want-reply and
 *    waits for localhost.run to acknowledge the exec/shell request. localhost.run may never answer
 *    (OpenSSH does not wait either) -> "channel request: timeout". Without it we just read the output.
 *  - The address must arrive within [URL_WAIT_MS]; otherwise the session is dropped and the next
 *    attempt switches between JSON (exec) and text (shell) mode.
 *  - The public URL changes on every reconnect; [Pointer] publishes it behind a fixed address.
 *  - Self-check: the public URL is probed periodically. The session is only replaced when the probe
 *    worked before on this URL and then fails [PROBE_FAILS_TO_KICK] times in a row, so a phone that
 *    cannot reach its own public URL never loops through new addresses.
 *  - [kick] reconnects immediately (e.g. Wi-Fi <-> mobile data switch).
 *
 * [State.message] and error texts are shown in the UI, hence Vietnamese.
 */
class Tunnel(
    private val localPort: Int,
    private val keyFile: File,
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

    /** Drop the current session and reconnect right away (no backoff). */
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

    // ───────── reconnect loop ─────────

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
                json = !json // this mode produced no address -> try the other one next time
            }
            val wait = if (kicked) 500L else backoff
            val up = (System.currentTimeMillis() - started) / 1000
            val why = (error?.let { "Lỗi localhost.run: $it" } ?: "Mất đường hầm localhost.run") +
                (if (kicked) " (nối lại theo yêu cầu)" else "") + " sau ${up}s"
            emit(State(Phase.RETRYING, null, "$why – thử lại sau ${wait / 1000}s…"))
            synchronized(lock) {
                if (alive && !kicked) try { lock.wait(wait) } catch (_: InterruptedException) { }
            }
            if (wait == backoff) backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    /** One SSH session. Returns true if a public address was received. */
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
        // like `-R 80:localhost:<port>`: OpenSSH sends bind address "localhost" when no host is given
        s.setPortForwardingR("localhost", 80, "127.0.0.1", localPort)

        val ch: Channel = if (json) {
            (s.openChannel("exec") as ChannelExec).apply { setCommand(JSON_COMMAND); setPty(false) }
        } else {
            (s.openChannel("shell") as ChannelShell).apply { setPty(false) }
        }
        val input = ch.inputStream
        ch.connect() // no timeout = no want-reply wait (see class comment)

        val found = waitForUrl(s, ch, input)
        emit(State(Phase.ONLINE, found, "Đường hầm sẵn sàng"))
        // keep reading for a new address (if any); the session stays up even if the channel closes
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

    /** Read output until an address shows up; throws with the output tail on timeout or channel close. */
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

    // ───────── self-check ─────────

    private fun probeLoop() {
        var bad = 0
        var provenUrl: String? = null // URL on which a probe has succeeded
        while (alive) {
            try { Thread.sleep(PROBE_EVERY_MS) } catch (_: InterruptedException) { break }
            val st = state
            val u = st.url
            if (st.phase != Phase.ONLINE || u == null) { bad = 0; continue }
            val err = probe("$u/health")
            if (err == null) {
                if (bad >= 2) Console.info("Tự kiểm tra địa chỉ: đã phản hồi lại")
                bad = 0; provenUrl = u
                continue
            }
            bad++
            if (bad == 2) Console.err("Tự kiểm tra địa chỉ thất bại ($err)")
            if (bad >= PROBE_FAILS_TO_KICK && provenUrl == u && state.url == u) {
                bad = 0
                Console.err("Địa chỉ hầm không còn phản hồi – nối lại")
                kick()
            }
        }
    }

    /** null = healthy, otherwise a short error description. */
    private fun probe(target: String): String? = try {
        val r = Net.request(target, timeoutMs = 10_000)
        if (r.code != 200) "HTTP ${r.code}"
        else if (!r.body.startsWith(HttpApi.HEALTH_BODY)) "nội dung lạ"
        else null
    } catch (t: Throwable) { Net.describe(t) }

    companion object {
        const val HOST = "localhost.run"
        const val PORT = 22
        const val USER = "nokey"
        const val JSON_COMMAND = "--output json"
        private const val URL_WAIT_MS = 40_000L
        private const val MIN_BACKOFF = 2_000L
        private const val MAX_BACKOFF = 30_000L
        private const val PROBE_EVERY_MS = 45_000L
        private const val PROBE_FAILS_TO_KICK = 3
    }
}

/** Extracts the public address from localhost.run output (pure, unit tested). */
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
