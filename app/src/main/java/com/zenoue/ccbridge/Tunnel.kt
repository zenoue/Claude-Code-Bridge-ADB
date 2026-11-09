package com.zenoue.ccbridge

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.File

/**
 * Đường hầm SSH ngược (như `ssh -R 80:localhost:8765 nokey@localhost.run`), chạy ngay trong app bằng JSch.
 * Không cần tài khoản, không cần cài thêm gì. Nhà cung cấp in ra URL https công khai; ta đọc URL đó
 * từ kênh shell. Rớt mạng thì tự kết nối lại (URL có thể đổi).
 */
class Tunnel(
    private val provider: Provider,
    private val localPort: Int,
    private val keyFile: File,
    private val onState: (state: String, url: String?) -> Unit,
) {
    data class Provider(
        val id: String, val label: String, val host: String, val port: Int, val user: String,
        val remotePort: Int, val urlHosts: List<String>,
    ) {
        companion object {
            val LOCALHOST_RUN = Provider("localhost.run", "localhost.run", "localhost.run", 22, "nokey", 80,
                listOf("lhr.life", "lhr.pro", "localhost.run"))
            val SERVEO = Provider("serveo", "serveo.net", "serveo.net", 22, "bridge", 80,
                listOf("serveo.net", "serveousercontent.com"))
            val ALL = listOf(LOCALHOST_RUN, SERVEO)
            fun of(id: String?) = ALL.firstOrNull { it.id == id } ?: LOCALHOST_RUN
        }
    }

    @Volatile private var alive = false
    @Volatile private var session: Session? = null
    @Volatile var url: String? = null; private set
    private var worker: Thread? = null

    fun start() {
        if (alive) return
        alive = true
        worker = Thread { loop() }.apply { isDaemon = true; name = "tunnel"; start() }
    }

    fun stop() {
        alive = false
        try { session?.disconnect() } catch (_: Throwable) { }
        worker?.interrupt()
        url = null
    }

    private fun loop() {
        var backoff = 2_000L
        while (alive) {
            val started = System.currentTimeMillis()
            try {
                connectOnce()
            } catch (t: Throwable) {
                if (alive) onState("Lỗi đường hầm: ${t.message ?: t}", null)
            }
            try { session?.disconnect() } catch (_: Throwable) { }
            session = null; url = null
            if (!alive) break
            // chạy ổn lâu thì reset thời gian chờ
            if (System.currentTimeMillis() - started > 60_000) backoff = 2_000L
            onState("Mất đường hầm – thử lại sau ${backoff / 1000}s…", null)
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun connectOnce() {
        onState("Đang mở đường hầm (${provider.label})…", null)
        val jsch = JSch()
        ensureKey(jsch)
        jsch.addIdentity(keyFile.absolutePath)
        val s = jsch.getSession(provider.user, provider.host, provider.port)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password,none")
        s.serverAliveInterval = 20_000
        s.serverAliveCountMax = 3
        session = s
        s.connect(20_000)
        s.setPortForwardingR("localhost", provider.remotePort, "127.0.0.1", localPort)

        val ch = s.openChannel("shell") as ChannelShell
        ch.setPty(false)
        val input = ch.inputStream
        ch.connect(15_000)

        val text = StringBuilder()
        val buf = ByteArray(4096)
        while (alive && s.isConnected && !ch.isClosed) {
            val n = try { input.read(buf) } catch (_: Throwable) { -1 }
            if (n < 0) break
            text.append(String(buf, 0, n, Charsets.UTF_8))
            if (text.length > 64 * 1024) text.delete(0, text.length - 8 * 1024)
            val found = findUrl(text, provider)
            if (found != null && found != url) {
                url = found
                onState("Đường hầm sẵn sàng", found)
            }
        }
        if (alive && url == null) throw IllegalStateException("không nhận được URL từ ${provider.label}: ${text.takeLast(200).trim()}")
        // kênh shell có thể đóng trong khi chuyển tiếp vẫn chạy: giữ đến khi phiên SSH rớt
        while (alive && s.isConnected) Thread.sleep(1000)
    }

    private fun ensureKey(jsch: JSch) {
        if (keyFile.exists() && keyFile.length() > 0) return
        keyFile.parentFile?.mkdirs()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        kp.writePrivateKey(keyFile.absolutePath)
        kp.dispose()
    }

    companion object {
        private val URL_RE = Regex("https://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)")

        /** Lấy URL đường hầm (subdomain thuộc nhà cung cấp, bỏ qua trang chủ/tài liệu). */
        fun findUrl(text: CharSequence, p: Provider): String? {
            for (m in URL_RE.findAll(text)) {
                val host = m.groupValues[1].lowercase()
                val ok = p.urlHosts.any { h -> host.endsWith(".$h") } &&
                    !host.startsWith("admin.") && !host.startsWith("www.") && !host.startsWith("docs.")
                if (ok) return "https://$host"
            }
            return null
        }
    }
}
