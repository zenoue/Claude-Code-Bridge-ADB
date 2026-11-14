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
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Loads and self-updates feature.dex (all app features) - the APK shell itself never changes.
 *
 *  - The copy bundled with the APK (assets/feature.dex) is always there; it is used until something
 *    newer is downloaded, or when a downloaded copy fails.
 *  - New versions come from the `live` branch (pushed by CI whenever feature code changes, no release):
 *    feature.json -> {version, api, sha256, file, notes}, then <file>; SHA-256 is verified and the file
 *    is stored read-only (required since Android 14). Several mirrors are tried because
 *    raw.githubusercontent.com is unreachable on some networks.
 *  - Only a feature with the same [API_VERSION] as the APK is loaded. A downloaded copy that crashes is
 *    marked bad and the bundled copy is used again.
 *  - Loaded classes cannot be unloaded -> applying a new version means restarting the app ([applyUpdate]).
 *
 * Status messages passed to [publish] are shown in the UI (Vietnamese or English, see [Lang]).
 */
object FeatureHost : HostApi {
    private const val CHECK_EVERY_MS = 30 * 60_000L
    private const val MIN_CHECK_GAP_MS = 10 * 60_000L
    private const val MAX_DEX = 16 * 1024 * 1024
    private const val LIVE_BRANCH = "live"

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private val dir: File get() = File(application.filesDir, "feature").apply { mkdirs() }

    @Volatile private var entryRef: FeatureEntry? = null
    @Volatile private var info = UpdateInfo("idle", Lang.t("Chưa kiểm tra cập nhật", "Not checked for updates yet"), 0, 0)
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

    // ───────── loading ─────────

    private class Candidate(val version: Int, val file: File, val source: String)

    /** Load the feature once per process: the GitHub copy if newer and valid, otherwise the bundled one. */
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

    /** Android 14+: dynamically loaded files must be read-only (set right after opening, as in Google's sample). */
    private fun writeReadOnly(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        FileOutputStream(tmp).use { os -> tmp.setReadOnly(); os.write(bytes) }
        target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("cannot write ${target.name}")
    }

    // ───────── failures ─────────

    private fun isBad(v: Int) = prefs.getBoolean("bad_$v", false)

    private fun markBad(v: Int, t: Throwable?) {
        prefs.edit().putBoolean("bad_$v", true)
            .putString("bad_reason", "#$v: ${t?.toString()?.take(300)}").commit()
        File(dir, "github-$v.dex").delete()
    }

    /** The GitHub copy threw through the Activity/Service lifecycle -> use the bundled copy next time. */
    fun onFeatureFailure(t: Throwable) {
        lastFailure = t
        if (featureSource == "github") markBad(featureVersion, t)
    }

    /** Uncaught crash with feature code in the stack trace -> mark the GitHub copy as bad. */
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

    // ───────── updating ─────────

    override fun updateInfo(): UpdateInfo = info

    override fun setUpdateListener(listener: Runnable?) { this.listener = listener }

    private fun publish(state: String, message: String, pending: Int = info.pending, checkedAt: Long = info.checkedAt) {
        info = UpdateInfo(state, message, pending, checkedAt)
        try { listener?.run() } catch (_: Throwable) { }
    }

    private fun refreshPendingInfo() {
        val dl = prefs.getInt("dl_version", 0)
        if (dl > featureVersion && downloaded() != null) {
            publish("downloaded", downloadedMessage(dl), dl)
        } else if (prefs.getString("bad_reason", null) != null && featureSource == "bundled" && dl > featureVersion) {
            publish("error", Lang.t("Bản tải về bị lỗi, đang dùng bản đi kèm (${prefs.getString("bad_reason", "")})", "The downloaded version failed; using the bundled one (${prefs.getString("bad_reason", "")})"), 0)
        }
    }

    override fun checkUpdate(force: Boolean) {
        if (!force && System.currentTimeMillis() - info.checkedAt < MIN_CHECK_GAP_MS) return
        if (!checking.compareAndSet(false, true)) return
        publish("checking", Lang.t("Đang kiểm tra GitHub…", "Checking GitHub…"))
        Thread {
            try { doCheck() } catch (t: Throwable) {
                publish("error", Lang.t("Không kiểm tra được: ${t.message ?: t}", "Could not check: ${t.message ?: t}"), checkedAt = System.currentTimeMillis())
            } finally { checking.set(false) }
        }.apply { isDaemon = true; name = "feature-check"; start() }
    }

    private fun doCheck() {
        val meta = JSONObject(fetch("feature.json", 64 * 1024).decodeToString())
        val v = meta.getInt("version")
        val api = meta.getInt("api")
        val now = System.currentTimeMillis()
        val have = maxOf(featureVersion, prefs.getInt("dl_version", 0).takeIf { downloaded() != null } ?: 0)
        when {
            api > API_VERSION -> publish("need_apk",
                Lang.t("Có chức năng #$v nhưng cần APK mới (API $api, APK đang là API $API_VERSION) – tải APK ở Releases", "Feature #$v needs a new APK (API $api, this APK is API $API_VERSION) – download the APK from Releases"), checkedAt = now)
            // live copy targets an older API than this APK -> unusable; the bundled copy is newer anyway
            api < API_VERSION -> publish("uptodate", Lang.t("Đang dùng bản mới nhất (#$featureVersion)", "Up to date (#$featureVersion)"), checkedAt = now)
            v <= have -> if (info.pending > 0) publish("downloaded", info.message, checkedAt = now)
                else publish("uptodate", Lang.t("Đang dùng bản mới nhất (#$featureVersion)", "Up to date (#$featureVersion)"), checkedAt = now)
            isBad(v) -> publish("error", Lang.t("Bản #$v bị lỗi trước đó – bỏ qua, chờ bản sau", "Version #$v failed before – skipped, waiting for the next one"), checkedAt = now)
            else -> {
                publish("checking", Lang.t("Đang tải chức năng #$v…", "Downloading feature #$v…"))
                val file = meta.optString("file", "feature-$v.dex")
                if (!Regex("[A-Za-z0-9._-]+").matches(file)) throw IllegalStateException("invalid file name: $file")
                val bytes = fetch(file, MAX_DEX)
                val sha = meta.getString("sha256")
                if (sha256(bytes) != sha) throw IllegalStateException("SHA-256 mismatch (update being published?)")
                writeReadOnly(File(dir, "github-$v.dex"), bytes)
                val notes = meta.optString("notes", "")
                prefs.edit().putInt("dl_version", v).putInt("dl_api", api).putString("dl_sha", sha)
                    .putString("dl_notes", notes).remove("bad_reason").commit()
                // remove old copies (except the running one and the one just downloaded)
                dir.listFiles { f -> f.name.startsWith("github-") }?.forEach {
                    if (it.name != "github-$v.dex" && it.name != "github-$featureVersion.dex") it.delete()
                }
                publish("downloaded", downloadedMessage(v), v, now)
            }
        }
    }

    private fun downloadedMessage(v: Int): String {
        val notes = prefs.getString("dl_notes", "").orEmpty()
        return Lang.t("Đã tải chức năng #$v – khởi động lại để dùng", "Feature #$v downloaded – restart to use it") + if (notes.isNotBlank()) Lang.t("\nMới: $notes", "\nNew: $notes") else ""
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
        publish("downloaded", Lang.t("Sẽ dùng bản đi kèm APK sau khi khởi động lại", "The version bundled with the APK will be used after a restart"), -1)
    }

    // ───────── helpers ─────────

    private class Mirror(val name: String, val url: (file: String) -> String, val headers: Map<String, String> = emptyMap())

    /** Where a file of the `live` branch can be fetched from, in order of preference. */
    private val mirrors: List<Mirror> get() = listOf(
        // ?t= defeats the ~5 minute CDN cache
        Mirror("raw", { "https://raw.githubusercontent.com/$repo/$LIVE_BRANCH/$it?t=${System.currentTimeMillis()}" }),
        Mirror("api", { "https://api.github.com/repos/$repo/contents/$it?ref=$LIVE_BRANCH" },
            mapOf("Accept" to "application/vnd.github.raw")),
        // may lag behind by a few hours; integrity is still checked by SHA-256
        Mirror("jsdelivr", { "https://cdn.jsdelivr.net/gh/$repo@$LIVE_BRANCH/$it" }),
    )

    private fun fetch(file: String, max: Int): ByteArray {
        val errors = ArrayList<String>()
        for (m in mirrors) {
            try { return get(m.url(file), m.headers, max) } catch (t: Throwable) {
                errors.add("${m.name}: ${t.javaClass.simpleName} ${t.message ?: ""}".trim())
            }
        }
        throw IllegalStateException(errors.joinToString(" | "))
    }

    /**
     * The bundled Conscrypt is the default TLS stack of this app (see [App]); if a handshake fails
     * with it, retry once with the platform's own TLS provider.
     */
    private val platformTls: SSLSocketFactory? by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), "AndroidOpenSSL")
            tmf.init(null as java.security.KeyStore?)
            SSLContext.getInstance("TLS", "AndroidOpenSSL").apply { init(null, tmf.trustManagers, null) }.socketFactory
        } catch (_: Throwable) { null }
    }

    private fun get(url: String, headers: Map<String, String>, max: Int): ByteArray = try {
        getOnce(url, headers, max, null)
    } catch (e: SSLException) {
        val f = platformTls ?: throw e
        getOnce(url, headers, max, f)
    }

    private fun getOnce(url: String, headers: Map<String, String>, max: Int, tls: SSLSocketFactory?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        if (tls != null && c is HttpsURLConnection) c.sslSocketFactory = tls
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "cc-bridge-adb/${BuildConfig.VERSION_NAME}")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { inp ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IllegalStateException("file too large")
                }
                return out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
