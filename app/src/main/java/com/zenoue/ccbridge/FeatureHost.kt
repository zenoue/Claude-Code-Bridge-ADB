package com.zenoue.ccbridge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import com.zenoue.ccbridge.api.API_VERSION
import com.zenoue.ccbridge.api.FEATURE_ENTRY_CLASS
import com.zenoue.ccbridge.api.FeatureEntry
import com.zenoue.ccbridge.api.HostApi
import com.zenoue.ccbridge.api.UpdateInfo
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nạp và tự cập nhật feature.dex (toàn bộ chức năng của app) – phần vỏ APK không đổi.
 *
 *  - Bản đi kèm APK (assets/feature.dex) luôn có, dùng khi chưa tải gì hoặc bản tải về lỗi.
 *  - Bản mới lấy từ GitHub Releases: releases/latest/download/feature.json -> {version, api, sha256, tag}
 *    rồi tải releases/download/<tag>/feature.dex, kiểm SHA‑256, lưu read‑only (bắt buộc từ Android 14).
 *  - Chỉ nạp bản có cùng [API_VERSION] với APK. Bản tải về gây crash -> đánh dấu hỏng, quay về bản đi kèm.
 *  - Lớp đã nạp không gỡ được khỏi tiến trình -> áp dụng bản mới = khởi động lại app ([applyUpdate]).
 */
object FeatureHost : HostApi {
    private const val CHECK_EVERY_MS = 30 * 60_000L
    private const val MIN_CHECK_GAP_MS = 10 * 60_000L
    private const val MAX_DEX = 16 * 1024 * 1024

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private val dir: File get() = File(application.filesDir, "feature").apply { mkdirs() }

    @Volatile private var entryRef: FeatureEntry? = null
    @Volatile private var info = UpdateInfo("idle", "Chưa kiểm tra cập nhật", 0, 0)
    @Volatile private var listener: Runnable? = null
    private val checking = AtomicBoolean(false)
    @Volatile var lastFailure: Throwable? = null; private set

    override val app: Application get() = application
    override val apiVersion: Int get() = API_VERSION
    override val apkVersion: String get() = BuildConfig.VERSION_NAME
    override val repo: String get() = BuildConfig.GITHUB_REPO
    override val notificationIcon: Int get() = R.drawable.ic_stat
    override var featureVersion = 0; private set
    override var featureSource = "bundled"; private set

    fun init(app: Application) {
        application = app
        prefs = app.getSharedPreferences("feature_host", Context.MODE_PRIVATE)
        Thread {
            while (true) {
                try { Thread.sleep(CHECK_EVERY_MS) } catch (_: InterruptedException) { break }
                checkUpdate(false)
            }
        }.apply { isDaemon = true; name = "feature-update"; start() }
    }

    override fun serviceIntent(): Intent = Intent(application, BridgeService::class.java)
    override fun activityIntent(): Intent = Intent(application, MainActivity::class.java)

    // ───────── nạp ─────────

    private class Candidate(val version: Int, val file: File, val source: String)

    /** Nạp feature một lần mỗi tiến trình: bản GitHub nếu mới hơn & hợp lệ, không thì bản đi kèm. */
    @Synchronized fun entry(): FeatureEntry {
        entryRef?.let { return it }
        val bundled = bundled()
        val dl = downloaded()?.takeIf { it.version > bundled.version }
        if (dl != null) {
            try { return load(dl) } catch (t: Throwable) { markBad(dl.version, t) }
        }
        return load(bundled)
    }

    private fun load(c: Candidate): FeatureEntry {
        val cl = DexClassLoader(c.file.absolutePath, null, null, FeatureHost::class.java.classLoader)
        val e = cl.loadClass(FEATURE_ENTRY_CLASS).getDeclaredConstructor().newInstance() as FeatureEntry
        featureVersion = c.version; featureSource = c.source
        e.attach(this)
        entryRef = e
        refreshPendingInfo()
        return e
    }

    private fun bundled(): Candidate {
        val meta = JSONObject(application.assets.open("feature.json").use { it.readBytes().decodeToString() })
        val sha = meta.getString("sha256")
        val file = File(dir, "bundled-${sha.take(16)}.dex")
        if (!file.exists() || sha256(file.readBytes()) != sha) {
            dir.listFiles { f -> f.name.startsWith("bundled-") }?.forEach { it.delete() }
            writeReadOnly(file, application.assets.open("feature.dex").use { it.readBytes() })
        }
        return Candidate(meta.getInt("version"), file, "bundled")
    }

    private fun downloaded(): Candidate? {
        val v = prefs.getInt("dl_version", 0)
        if (v <= 0 || isBad(v) || prefs.getInt("dl_api", -1) != API_VERSION) return null
        val f = File(dir, "github-$v.dex")
        if (!f.exists() || sha256(f.readBytes()) != prefs.getString("dl_sha", "")) return null
        return Candidate(v, f, "github")
    }

    /** Android 14+: file nạp động phải read‑only (đặt ngay khi mở luồng ghi, như ví dụ của Google). */
    private fun writeReadOnly(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        FileOutputStream(tmp).use { os -> tmp.setReadOnly(); os.write(bytes) }
        target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("không ghi được ${target.name}")
    }

    // ───────── lỗi & đánh dấu hỏng ─────────

    private fun isBad(v: Int) = prefs.getBoolean("bad_$v", false)

    private fun markBad(v: Int, t: Throwable?) {
        prefs.edit().putBoolean("bad_$v", true)
            .putString("bad_reason", "v1.0.$v: ${t?.toString()?.take(300)}").commit()
        File(dir, "github-$v.dex").delete()
    }

    /** Feature bản GitHub ném lỗi qua vòng đời Activity/Service -> lần sau dùng bản đi kèm. */
    fun onFeatureFailure(t: Throwable) {
        lastFailure = t
        if (featureSource == "github") markBad(featureVersion, t)
    }

    /** Crash không bắt được mà có dấu vết code feature -> đánh dấu bản GitHub hỏng. */
    fun onCrash(t: Throwable) {
        if (!::prefs.isInitialized || featureSource != "github") return
        var c: Throwable? = t
        while (c != null) {
            if (c.stackTrace.any { it.className.startsWith("com.zenoue.ccbridge.feature.") }) {
                markBad(featureVersion, t); return
            }
            c = c.cause
        }
    }

    // ───────── cập nhật ─────────

    override fun updateInfo(): UpdateInfo = info

    override fun setUpdateListener(listener: Runnable?) { this.listener = listener }

    private fun publish(state: String, message: String, pending: Int = info.pending, checkedAt: Long = info.checkedAt) {
        info = UpdateInfo(state, message, pending, checkedAt)
        try { listener?.run() } catch (_: Throwable) { }
    }

    private fun refreshPendingInfo() {
        val dl = prefs.getInt("dl_version", 0)
        if (dl > featureVersion && downloaded() != null) {
            publish("downloaded", "Đã tải chức năng v1.0.$dl – khởi động lại để dùng", dl)
        } else if (prefs.getString("bad_reason", null) != null && featureSource == "bundled" && dl > featureVersion) {
            publish("error", "Bản tải về bị lỗi, đang dùng bản đi kèm (${prefs.getString("bad_reason", "")})", 0)
        }
    }

    override fun checkUpdate(force: Boolean) {
        if (!force && System.currentTimeMillis() - info.checkedAt < MIN_CHECK_GAP_MS) return
        if (!checking.compareAndSet(false, true)) return
        publish("checking", "Đang kiểm tra GitHub…")
        Thread {
            try { doCheck() } catch (t: Throwable) {
                publish("error", "Không kiểm tra được: ${t.message ?: t}", checkedAt = System.currentTimeMillis())
            } finally { checking.set(false) }
        }.apply { isDaemon = true; name = "feature-check"; start() }
    }

    private fun doCheck() {
        val meta = JSONObject(get("https://github.com/$repo/releases/latest/download/feature.json", 64 * 1024).decodeToString())
        val v = meta.getInt("version")
        val api = meta.getInt("api")
        val now = System.currentTimeMillis()
        val have = maxOf(featureVersion, prefs.getInt("dl_version", 0).takeIf { downloaded() != null } ?: 0)
        when {
            api != API_VERSION -> publish("need_apk",
                "Có chức năng v1.0.$v nhưng cần APK mới (API $api, APK đang là API $API_VERSION) – tải APK ở Releases", checkedAt = now)
            v <= have -> if (info.pending > 0) publish("downloaded", info.message, checkedAt = now)
                else publish("uptodate", "Đang dùng bản mới nhất (v1.0.$featureVersion)", checkedAt = now)
            isBad(v) -> publish("error", "Bản v1.0.$v bị lỗi trước đó – bỏ qua, chờ bản sau", checkedAt = now)
            else -> {
                publish("checking", "Đang tải chức năng v1.0.$v…")
                val tag = meta.optString("tag", "v1.0.$v")
                val bytes = get("https://github.com/$repo/releases/download/$tag/feature.dex", MAX_DEX)
                val sha = meta.getString("sha256")
                if (sha256(bytes) != sha) throw IllegalStateException("SHA‑256 không khớp (bản phát hành đang cập nhật?)")
                writeReadOnly(File(dir, "github-$v.dex"), bytes)
                prefs.edit().putInt("dl_version", v).putInt("dl_api", api).putString("dl_sha", sha)
                    .remove("bad_reason").commit()
                // dọn bản cũ (trừ bản đang chạy và bản vừa tải)
                dir.listFiles { f -> f.name.startsWith("github-") }?.forEach {
                    if (it.name != "github-$v.dex" && it.name != "github-$featureVersion.dex") it.delete()
                }
                publish("downloaded", "Đã tải chức năng v1.0.$v – khởi động lại để dùng", v, now)
            }
        }
    }

    override fun applyUpdate(restartService: Boolean) {
        try { application.stopService(serviceIntent()) } catch (_: Throwable) { }
        application.startActivity(
            Intent(application, RestartActivity::class.java)
                .putExtra(RestartActivity.EXTRA_PID, Process.myPid())
                .putExtra(MainActivity.EXTRA_START_SERVICE, restartService)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun resetToBundled() {
        if (featureSource == "github") prefs.edit().putBoolean("bad_$featureVersion", true).commit()
        prefs.edit().remove("dl_version").remove("dl_sha").remove("dl_api").commit()
        publish("downloaded", "Sẽ dùng bản đi kèm APK sau khi khởi động lại", -1)
    }

    // ───────── tiện ích ─────────

    private fun get(url: String, max: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "cc-bridge-adb/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode} từ ${URL(url).path}")
            c.inputStream.use { inp ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IllegalStateException("file quá lớn")
                }
                return out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
