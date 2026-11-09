package com.zenoue.ccbridge

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
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
        if (!connected) lastError = "adbd từ chối (chưa ghép nối hoặc sai cổng)"
        else lastError = null
        connected
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); connected = false; false }

    /** Tự dò cổng kết nối qua mDNS rồi kết nối (Android 11+). */
    fun autoConnect(ctx: Context, timeoutMs: Long = 8000): Boolean = try {
        connected = AdbManager.get(ctx).connectTls(ctx, timeoutMs)
        lastError = if (connected) null else "không tìm thấy adbd qua mDNS"
        connected
    } catch (t: Throwable) { lastError = t.message ?: t.toString(); connected = false; false }

    fun disconnect(ctx: Context) {
        try { AdbManager.get(ctx).disconnect() } catch (_: Throwable) { }
        connected = false
    }

    /** Mở `adb shell <cmd>` và trả stream để đọc dần (dùng cho logcat). */
    fun openShell(ctx: Context, cmd: String): AdbStream =
        AdbManager.get(ctx).openStream("shell:$cmd")

    private const val EXIT_MARK = "__CCB_EXIT__="

    /**
     * Chạy lệnh rồi trả toàn bộ output kèm exit code thật.
     * Lệnh được nối thêm một dòng `echo marker$?` (xuống dòng để không dính vào comment/`&` của lệnh).
     */
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "adb-timeout").apply { isDaemon = true }
    }

    /** [timeoutMs] > 0: quá giờ thì đóng stream, trả phần output đã có với exit 124. */
    fun run(ctx: Context, cmd: String, timeoutMs: Long = 0, maxLen: Int = 8 * 1024 * 1024): Result {
        val stream = openShell(ctx, "$cmd\necho $EXIT_MARK$?")
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val killer = if (timeoutMs > 0) timer.schedule({
            timedOut.set(true); try { stream.close() } catch (_: Throwable) { }
        }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) else null
        val sb = StringBuilder()
        var cut = false
        try {
            BufferedReader(InputStreamReader(stream.openInputStream())).use { r ->
                val buf = CharArray(16 * 1024)
                while (true) {
                    val n = try { r.read(buf) } catch (t: Throwable) { if (timedOut.get()) -1 else throw t }
                    if (n < 0) break
                    if (sb.length < maxLen) sb.appendRange(buf, 0, n) else cut = true
                }
            }
        } finally {
            killer?.cancel(false)
            try { stream.close() } catch (_: Throwable) { }
        }
        if (timedOut.get()) {
            return Result(124, sb.toString() + "\n…(hết thời gian ${timeoutMs / 1000}s, đã dừng lệnh)", "ADB (shell)")
        }
        val (exit, out) = splitExit(sb.toString())
        return Result(exit, if (cut) "$out\n…(đã cắt bớt)" else out, "ADB (shell)")
    }

    /** Tách dòng `marker<exit>` cuối output. Không thấy marker -> exit 0, giữ nguyên output. */
    fun splitExit(raw: String): Pair<Int, String> {
        val i = raw.lastIndexOf(EXIT_MARK)
        if (i < 0) return 0 to raw
        val exit = raw.substring(i + EXIT_MARK.length).trim().toIntOrNull() ?: 0
        return exit to raw.substring(0, i).removeSuffix("\n")
    }
}
