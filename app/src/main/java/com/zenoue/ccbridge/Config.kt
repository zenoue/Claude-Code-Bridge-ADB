package com.zenoue.ccbridge

import android.content.Context
import java.io.File

/** Cấu hình cầu nối, lưu nội bộ trong app. */
class Config(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    // Chung
    var password: String get() = sp.getString("password", "") ?: ""; set(v) = put("password", v)
    var allowWrite: Boolean get() = sp.getBoolean("allowWrite", false); set(v) = sp.edit().putBoolean("allowWrite", v).apply()

    // Đường hầm (mặc định bật)
    var tunnelEnabled: Boolean get() = sp.getBoolean("tunnelEnabled", true); set(v) = sp.edit().putBoolean("tunnelEnabled", v).apply()
    var provider: String get() = sp.getString("provider", Tunnel.Provider.LOCALHOST_RUN.id) ?: ""; set(v) = put("provider", v)
    var httpPort: Int get() = sp.getInt("httpPort", 8765); set(v) = sp.edit().putInt("httpPort", v).apply()
    val sshKey: File get() = File(ctx.filesDir, "tunnel_key")

    // Supabase (tuỳ chọn)
    var supabaseEnabled: Boolean get() = sp.getBoolean("supabaseEnabled", sp.contains("url")); set(v) = sp.edit().putBoolean("supabaseEnabled", v).apply()
    var url: String get() = sp.getString("url", "") ?: ""; set(v) = put("url", v.trim().trimEnd('/'))
    var key: String get() = sp.getString("key", "") ?: ""; set(v) = put("key", v.trim())
    var lastSeq: Long get() = sp.getLong("lastSeq", 0L); set(v) = sp.edit().putLong("lastSeq", v).apply()

    // ADB không dây (đã ghép nối)
    var adbHost: String get() = sp.getString("adbHost", "127.0.0.1") ?: "127.0.0.1"; set(v) = put("adbHost", v.trim().ifEmpty { "127.0.0.1" })
    var adbPort: Int get() = sp.getInt("adbPort", 0); set(v) = sp.edit().putInt("adbPort", v).apply()
    var paired: Boolean get() = sp.getBoolean("paired", false); set(v) = sp.edit().putBoolean("paired", v).apply()

    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    val passwordOk: Boolean get() = password.length >= MIN_PASSWORD
    val supabaseReady: Boolean get() = url.startsWith("http") && key.isNotEmpty()

    /** Đủ để bật cầu nối: có mật khẩu đủ mạnh và ít nhất một kênh. */
    val ready: Boolean get() = passwordOk && (tunnelEnabled || (supabaseEnabled && supabaseReady))

    fun bridge(): SupabaseBridge = SupabaseBridge(url, key)

    companion object {
        const val MIN_PASSWORD = 12
    }
}
