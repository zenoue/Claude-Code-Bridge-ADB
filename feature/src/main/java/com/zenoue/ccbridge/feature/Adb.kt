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
 * ADB chạy ngay trong app (qua libadb-android): tự ghép nối "Gỡ lỗi không dây"
 * rồi mở `adb shell` tới chính máy qua TLS. Không root, không Shizuku, không PC.
 *
 * Khoá RSA + chứng chỉ của app được sinh một lần và lưu nội bộ; khi ghép nối lần đầu
 * điện thoại hiện hộp thoại "Cho phép gỡ lỗi" với vân tay của khoá này.
 */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {

    private val storedKey: PrivateKey
    private val storedCert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        // mặc định libadb chờ vô hạn; đủ lâu để người dùng bấm "Cho phép gỡ lỗi USB?" khi nối cổng TCP lần đầu
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

/** Mặt ngoài gọn cho phần còn lại của app. */
object Adb {
    data class Result(val exit: Int, val output: String, val via: String)

    @Volatile var connected = false; private set
    @Volatile var lastError: String? = null; private set
    /** Kiểu kết nối hiện tại: "không dây (TLS)" hoặc "TCP 5555 (không cần Wi‑Fi)". */
    @Volatile var mode: String = ""; private set

    /** Cổng TCP cổ điển (như `adb tcpip 5555`) – dùng được khi không có Wi‑Fi. */
    const val TCP_PORT = 5555

    fun isConnected(ctx: Context): Boolean = try {
        AdbManager.get(ctx).isConnected.also { connected = it }
    } catch (_: Throwable) { false }

    /** Ghép nối một lần với "Gỡ lỗi không dây" (host:port + mã 6 số). */
    fun pair(ctx: Context, host: String, port: Int, code: String): Boolean = try {
        AdbManager.get(ctx).pair(host, port, code); lastError = null; true
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); false }

    /** Kết nối tới adbd (đã ghép nối). */
    fun connect(ctx: Context, host: String, port: Int): Boolean = try {
        connected = AdbManager.get(ctx).connect(host, port)
        if (!connected) lastError = "adbd từ chối (chưa ghép nối, sai cổng hoặc chưa bấm Cho phép)"
        else { lastError = null; mode = if (port == TCP_PORT) "TCP $TCP_PORT (không cần Wi‑Fi)" else "không dây (TLS)" }
        connected
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); connected = false; false }

    /** Tự dò cổng kết nối qua mDNS rồi kết nối (Android 11+). */
    fun autoConnect(ctx: Context, timeoutMs: Long = 8000): Boolean = try {
        connected = AdbManager.get(ctx).connectTls(ctx, timeoutMs)
        lastError = if (connected) null else "không tìm thấy adbd qua mDNS"
        if (connected) mode = "không dây (TLS)"
        connected
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); connected = false; false }

    /** Lệnh lỗi: chỉ ngắt ADB khi kết nối thật sự đã chết (lỗi của một stream không làm rớt cả kết nối). */
    fun onCommandError(ctx: Context, t: Throwable): Result {
        val alive = try { AdbManager.get(ctx).isConnected } catch (_: Throwable) { false }
        if (!alive) disconnect(ctx)
        return Result(-1, (if (alive) "" else "ADB mất kết nối: ") + (t.message ?: t.toString()), "ADB")
    }

    fun disconnect(ctx: Context) {
        try { AdbManager.get(ctx).disconnect() } catch (_: Throwable) { }
        connected = false; mode = ""
    }

    /** adbd có đang nghe cổng TCP cổ điển trên máy không (kiểm tra nhanh, không xác thực). */
    fun tcpPortOpen(port: Int = TCP_PORT): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 600); true }
    } catch (_: Throwable) { false }

    /**
     * Như `adb tcpip <port>`: adbd khởi động lại và nghe thêm cổng TCP thường. Cổng này nằm trên chính máy
     * (127.0.0.1) nên không cần Wi‑Fi – tắt Wi‑Fi/dùng 4G vẫn nối được, cho tới khi khởi động lại điện thoại.
     * Kết nối hiện tại sẽ rớt (adbd khởi động lại). Trả thông báo của adbd.
     */
    fun enableTcpip(ctx: Context, port: Int = TCP_PORT): String = restartService(ctx, "tcpip:$port")

    /** Như `adb usb`: adbd bỏ cổng TCP cổ điển (Gỡ lỗi không dây qua Wi‑Fi vẫn dùng được). */
    fun disableTcpip(ctx: Context): String = restartService(ctx, "usb:")

    private fun restartService(ctx: Context, service: String): String {
        val st = AdbManager.get(ctx).openStream(service)
        val msg = try { readAll(st, 5_000).trim() } catch (_: Throwable) { "" } finally {
            try { st.close() } catch (_: Throwable) { }
        }
        disconnect(ctx)
        return msg.ifEmpty { "(adbd không phản hồi – có thể đã khởi động lại)" }
    }

    /** Nối 127.0.0.1:<port>, thử lại vài lần trong lúc adbd khởi động lại. */
    fun connectTcp(ctx: Context, port: Int = TCP_PORT, attempts: Int = 8): Boolean {
        repeat(attempts) {
            if (tcpPortOpen(port) && connect(ctx, "127.0.0.1", port)) return true
            Thread.sleep(1000)
        }
        if (lastError == null || !tcpPortOpen(port)) lastError = "cổng $port chưa mở (bấm \"Bật ADB không cần Wi‑Fi\" khi đang có Wi‑Fi)"
        return false
    }

    /** Mở `adb shell <cmd>` và trả stream để đọc dần (dùng cho logcat). Lệnh được bọc base64 ([wrap]). */
    fun openShell(ctx: Context, cmd: String): AdbStream =
        AdbManager.get(ctx).openStream("shell:" + wrap(cmd))

    private const val EXIT_MARK = "__CCB_EXIT__="

    /**
     * Bọc lệnh: `sh -c "$(echo <base64> | base64 -d)"`.
     *  - libadb cấp bộ đệm lệnh theo số ký tự nhưng ghi số byte UTF‑8 -> lệnh có chữ Việt/emoji gây
     *    BufferOverflowException. Base64 chỉ có ASCII.
     *  - Chạy trong `sh -c` riêng nên `exit N`, comment `#`, `&`, dấu nháy… không phá phần đánh dấu exit code.
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
     * Đọc AdbStream an toàn, tránh hai lỗi của libadb (AdbStream.read):
     *  - stream bị adbd đóng khi hàng đợi rỗng -> ném IOException("Stream closed.") thay vì EOF;
     *  - adbd đóng khi còn dữ liệu -> đọc hết rồi `read()` chờ vĩnh viễn.
     * Chỉ gọi read() khi available() > 0, còn lại tự kiểm tra trạng thái đóng.
     * Trả true = hết stream, false = quá [deadlineMs] (0 = không giới hạn) hoặc [stop] trả true.
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

    /** Đọc hết stream thành chuỗi UTF‑8 (dùng cho service ngắn như tcpip:/usb:). */
    fun readAll(st: AdbStream, timeoutMs: Long): String {
        val out = java.io.ByteArrayOutputStream()
        pump(st, System.currentTimeMillis() + timeoutMs) { b, n -> out.write(b, 0, n) }
        return out.toString("UTF-8")
    }

    /**
     * Chạy lệnh rồi trả toàn bộ output kèm exit code thật (nối `echo marker$?` sau lệnh đã bọc).
     * [timeoutMs] > 0: quá giờ thì đóng stream, trả phần output đã có với exit 124.
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
            return Result(124, text + "\n…(hết thời gian ${timeoutMs / 1000}s, đã dừng lệnh)", "ADB (shell)")
        }
        val (exit, body) = splitExit(text)
        return Result(exit, if (cut) "$body\n…(đã cắt bớt)" else body, "ADB (shell)")
    }

    /** Tách dòng `marker<exit>` cuối output. Không thấy marker -> exit 0, giữ nguyên output. */
    fun splitExit(raw: String): Pair<Int, String> {
        val i = raw.lastIndexOf(EXIT_MARK)
        if (i < 0) return 0 to raw
        val exit = raw.substring(i + EXIT_MARK.length).trim().toIntOrNull() ?: 0
        return exit to raw.substring(0, i).removeSuffix("\n")
    }
}
