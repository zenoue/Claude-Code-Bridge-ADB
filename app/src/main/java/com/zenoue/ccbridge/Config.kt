package com.zenoue.ccbridge

import android.content.Context
import java.io.File
import java.security.SecureRandom

/** Cấu hình, lưu nội bộ trong app. */
class Config(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    init {
        // dọn cấu hình của bản cũ (mật khẩu ký lệnh không còn dùng)
        if (sp.contains("password")) sp.edit().remove("password").remove("provider").apply()
    }

    var allowWrite: Boolean get() = sp.getBoolean("allowWrite", false); set(v) = sp.edit().putBoolean("allowWrite", v).apply()

    // Đường hầm
    var provider: String get() = sp.getString("tunnelProvider", Tunnel.Provider.AUTO) ?: Tunnel.Provider.AUTO; set(v) = put("tunnelProvider", v)
    var httpPort: Int get() = sp.getInt("httpPort", 8765); set(v) = sp.edit().putInt("httpPort", v).apply()
    val sshKey: File get() = File(ctx.filesDir, "tunnel_key")

    /** Khoá ngẫu nhiên nằm trong địa chỉ đường hầm (tự sinh, người dùng không cần nhập). */
    val urlKey: String
        get() = synchronized(LOCK) { sp.getString("urlKey", null)?.takeIf { it.length >= 16 } ?: newUrlKey() }

    /** Sinh khoá mới -> địa chỉ cũ hết hiệu lực. */
    fun newUrlKey(): String = synchronized(LOCK) { randomKey().also { sp.edit().putString("urlKey", it).commit() } }

    // ADB không dây (đã ghép nối)
    var adbHost: String get() = sp.getString("adbHost", "127.0.0.1") ?: "127.0.0.1"; set(v) = put("adbHost", v.trim().ifEmpty { "127.0.0.1" })
    var adbPort: Int get() = sp.getInt("adbPort", 0); set(v) = sp.edit().putInt("adbPort", v).apply()
    var paired: Boolean get() = sp.getBoolean("paired", false); set(v) = sp.edit().putBoolean("paired", v).apply()

    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    companion object {
        private val LOCK = Any()
        private const val KEY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        fun randomKey(len: Int = 24): String {
            val r = SecureRandom()
            return (1..len).map { KEY_CHARS[r.nextInt(KEY_CHARS.length)] }.joinToString("")
        }
    }
}
