package com.zenoue.ccbridge

import android.content.Context
import java.io.File

/** Cấu hình, lưu nội bộ trong app. */
class Config(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var password: String get() = sp.getString("password", "") ?: ""; set(v) = put("password", v)
    var allowWrite: Boolean get() = sp.getBoolean("allowWrite", false); set(v) = sp.edit().putBoolean("allowWrite", v).apply()

    // Đường hầm
    var provider: String get() = sp.getString("provider", Tunnel.Provider.LOCALHOST_RUN.id) ?: ""; set(v) = put("provider", v)
    var httpPort: Int get() = sp.getInt("httpPort", 8765); set(v) = sp.edit().putInt("httpPort", v).apply()
    val sshKey: File get() = File(ctx.filesDir, "tunnel_key")

    // ADB không dây (đã ghép nối)
    var adbHost: String get() = sp.getString("adbHost", "127.0.0.1") ?: "127.0.0.1"; set(v) = put("adbHost", v.trim().ifEmpty { "127.0.0.1" })
    var adbPort: Int get() = sp.getInt("adbPort", 0); set(v) = sp.edit().putInt("adbPort", v).apply()
    var paired: Boolean get() = sp.getBoolean("paired", false); set(v) = sp.edit().putBoolean("paired", v).apply()

    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    /** Đủ để bật cầu nối. */
    val ready: Boolean get() = password.length >= MIN_PASSWORD

    companion object {
        const val MIN_PASSWORD = 12
    }
}
