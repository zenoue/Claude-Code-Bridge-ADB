package com.zenoue.ccbridge.feature

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * ADB client running inside the app (libadb-android): pairs with "Wireless debugging" and opens
 * `adb shell` to the device itself over TLS. No root, no Shizuku, no PC.
 *
 * The app's RSA key + certificate are generated once and stored privately; on first use the phone
 * shows the "Allow debugging" dialog with this key's fingerprint.
 */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {

    private val storedKey: PrivateKey
    private val storedCert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        // libadb waits forever by default; 90s is enough for the user to tap "Allow USB debugging?"
        // the first time the plain TCP port is used
        setTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        val keyFile = File(ctx.filesDir, "adb_key.pk8")
        val certFile = File(ctx.filesDir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            storedKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            storedCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=CC Bridge ADB, OU=Bridge, O=Zenoue")
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                Date(now + 10L * 365 * 86_400_000L), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            storedKey = kp.private
            storedCert = cert
            keyFile.writeBytes(storedKey.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = storedKey
    override fun getCertificate(): Certificate = storedCert
    override fun getDeviceName(): String = "CC-Bridge-ADB"

    companion object {
        @Volatile private var inst: AdbManager? = null
        fun get(ctx: Context): AdbManager =
            inst ?: synchronized(this) { inst ?: AdbManager(ctx.applicationContext).also { inst = it } }
    }
}

/** Small facade used by the rest of the app. */
object Adb {
    data class Result(val exit: Int, val output: String, val via: String)

    @Volatile var connected = false; private set
    @Volatile var lastError: String? = null; private set
    /** True when connected through the classic TCP port (no Wi-Fi needed), false for wireless debugging (TLS). */
    @Volatile var viaTcp = false; private set

    /** Classic TCP port (like `adb tcpip 5555`) - works without Wi-Fi. */
    const val TCP_PORT = 5555

    fun isConnected(ctx: Context): Boolean = try {
        AdbManager.get(ctx).isConnected.also { connected = it }
    } catch (_: Throwable) { false }

    /** One-time pairing with "Wireless debugging" (host:port + 6-digit code). */
    fun pair(ctx: Context, host: String, port: Int, code: String): Boolean = try {
        AdbManager.get(ctx).pair(host, port, code); lastError = null; true
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); false }

    /** Connect to adbd (already paired). [lastError] is shown in the UI, hence Vietnamese. */
    fun connect(ctx: Context, host: String, port: Int): Boolean = try {
        connected = AdbManager.get(ctx).connect(host, port)
        if (!connected) lastError = "adbd từ chối (chưa ghép nối, sai cổng hoặc chưa bấm Cho phép)"
        else { lastError = null; viaTcp = port == TCP_PORT }
        connected
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); connected = false; false }

    /** Discover the connect port through mDNS, then connect (Android 11+). */
    fun autoConnect(ctx: Context, timeoutMs: Long = 8000): Boolean = try {
        connected = AdbManager.get(ctx).connectTls(ctx, timeoutMs)
        lastError = if (connected) null else "không tìm thấy adbd qua mDNS"
        if (connected) viaTcp = false
        connected
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); connected = false; false }

    /** A command failed: drop ADB only if the connection is really dead (one broken stream must not kill it). */
    fun onCommandError(ctx: Context, t: Throwable): Result {
        val alive = try { AdbManager.get(ctx).isConnected } catch (_: Throwable) { false }
        if (!alive) disconnect(ctx)
        return Result(-1, (if (alive) "" else "ADB connection lost: ") + (t.message ?: t.toString()), "ADB")
    }

    fun disconnect(ctx: Context) {
        try { AdbManager.get(ctx).disconnect() } catch (_: Throwable) { }
        connected = false; viaTcp = false
    }

    /** Is adbd listening on the classic TCP port on this device (quick check, no authentication). */
    fun tcpPortOpen(port: Int = TCP_PORT): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 600); true }
    } catch (_: Throwable) { false }

    /**
     * Like `adb tcpip <port>`: adbd restarts and also listens on a plain TCP port. The port is on the device
     * itself (127.0.0.1), so no Wi-Fi is needed - it keeps working on mobile data until the phone reboots.
     * The current connection drops (adbd restarts). Returns adbd's message.
     */
    fun enableTcpip(ctx: Context, port: Int = TCP_PORT): String = restartService(ctx, "tcpip:$port")

    /** Like `adb usb`: adbd closes the classic TCP port (wireless debugging over Wi-Fi keeps working). */
    fun disableTcpip(ctx: Context): String = restartService(ctx, "usb:")

    private fun restartService(ctx: Context, service: String): String {
        val st = AdbManager.get(ctx).openStream(service)
        val msg = try { readAll(st, 5_000).trim() } catch (_: Throwable) { "" } finally {
            try { st.close() } catch (_: Throwable) { }
        }
        disconnect(ctx)
        return msg.ifEmpty { "(adbd không phản hồi – có thể đã khởi động lại)" }
    }

    /** Connect to 127.0.0.1:<port>, retrying a few times while adbd restarts. */
    fun connectTcp(ctx: Context, port: Int = TCP_PORT, attempts: Int = 8): Boolean {
        repeat(attempts) {
            if (tcpPortOpen(port) && connect(ctx, "127.0.0.1", port)) return true
            Thread.sleep(1000)
        }
        if (lastError == null || !tcpPortOpen(port)) lastError = "cổng $port chưa mở (bấm \"Bật ADB không cần Wi‑Fi\" khi đang có Wi‑Fi)"
        return false
    }

    /** Open `adb shell <cmd>` and return the stream for incremental reading (logcat). The command is wrapped ([wrap]). */
    fun openShell(ctx: Context, cmd: String): AdbStream =
        AdbManager.get(ctx).openStream("shell:" + wrap(cmd))

    private const val EXIT_MARK = "__CCB_EXIT__="

    /**
     * Wrap a command as `sh -c "$(echo <base64> | base64 -d)"`.
     *  - libadb sizes the OPEN payload by character count but writes UTF-8 bytes, so non-ASCII commands
     *    throw BufferOverflowException. Base64 is ASCII only.
     *  - Running in its own `sh -c` keeps `exit N`, `#` comments, `&` and quotes from breaking the
     *    exit-code marker appended by [run].
     */
    fun wrap(cmd: String): String {
        val b64 = android.util.Base64.encodeToString(cmd.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "sh -c \"\$(echo $b64 | base64 -d)\""
    }

    private val pendingCloseField = runCatching {
        AdbStream::class.java.getDeclaredField("mPendingClose").apply { isAccessible = true }
    }.getOrNull()

    private fun pendingClose(st: AdbStream): Boolean =
        try { pendingCloseField?.getBoolean(st) == true } catch (_: Throwable) { false }

    /**
     * Read an AdbStream safely, working around two libadb bugs in AdbStream.read():
     *  - adbd closes the stream while the queue is empty -> IOException("Stream closed.") instead of EOF;
     *  - adbd closes while data is still queued -> after draining, `read()` blocks forever.
     * read() is only called when available() > 0; the closed state is checked here instead.
     * Returns true at end of stream, false when [deadlineMs] passed (0 = no limit) or [stop] returned true.
     */
    fun pump(st: AdbStream, deadlineMs: Long = 0, stop: () -> Boolean = { false }, sink: (ByteArray, Int) -> Unit): Boolean {
        val buf = ByteArray(64 * 1024)
        var idle = 1L
        while (true) {
            val avail = try { st.available() } catch (_: java.io.IOException) { return true }
            if (avail > 0) {
                val n = try { st.read(buf, 0, buf.size) } catch (_: java.io.IOException) { return true }
                if (n < 0) return true
                if (n > 0) sink(buf, n)
                idle = 1
                continue
            }
            if (st.isClosed || pendingClose(st)) {
                val left = try { st.available() } catch (_: java.io.IOException) { 0 }
                if (left == 0) return true else continue
            }
            if (stop()) return false
            if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs) return false
            try { Thread.sleep(idle) } catch (_: InterruptedException) { return false }
            idle = (idle * 2).coerceAtMost(20)
        }
    }

    /** Read the whole stream as UTF-8 (for short services such as tcpip:/usb:). */
    fun readAll(st: AdbStream, timeoutMs: Long): String {
        val out = java.io.ByteArrayOutputStream()
        pump(st, System.currentTimeMillis() + timeoutMs) { b, n -> out.write(b, 0, n) }
        return out.toString("UTF-8")
    }

    /**
     * Run a command and return its full output with the real exit code (`echo marker$?` is appended
     * after the wrapped command). [timeoutMs] > 0: on timeout the stream is closed and the output so far
     * is returned with exit 124.
     */
    fun run(ctx: Context, cmd: String, timeoutMs: Long = 0, maxLen: Int = 8 * 1024 * 1024): Result {
        val stream = AdbManager.get(ctx).openStream("shell:" + wrap(cmd) + "; echo $EXIT_MARK\$?")
        val out = java.io.ByteArrayOutputStream()
        var cut = false
        val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L
        val finished = try {
            pump(stream, deadline) { b, n -> if (out.size() < maxLen) out.write(b, 0, n) else cut = true }
        } finally {
            try { stream.close() } catch (_: Throwable) { }
        }
        val text = out.toString("UTF-8")
        if (!finished) {
            return Result(124, text + "\n...(timed out after ${timeoutMs / 1000}s, command stopped)", "ADB (shell)")
        }
        val (exit, body) = splitExit(text)
        return Result(exit, if (cut) "$body\n...(output truncated)" else body, "ADB (shell)")
    }

    /** Split the trailing `marker<exit>` line off the output. No marker -> exit 0, output unchanged. */
    fun splitExit(raw: String): Pair<Int, String> {
        val i = raw.lastIndexOf(EXIT_MARK)
        if (i < 0) return 0 to raw
        val exit = raw.substring(i + EXIT_MARK.length).trim().toIntOrNull() ?: 0
        return exit to raw.substring(0, i).removeSuffix("\n")
    }
}
