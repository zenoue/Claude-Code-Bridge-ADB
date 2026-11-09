package com.zenoue.ccbridge

import android.content.Context

/** Cấu hình cầu nối Supabase, lưu nội bộ trong app. */
class Config(ctx: Context) {
    private val sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    // Supabase
    var url: String get() = sp.getString("url", "") ?: ""; set(v) = put("url", v.trim().trimEnd('/'))
    var key: String get() = sp.getString("key", "") ?: ""; set(v) = put("key", v.trim())
    var password: String get() = sp.getString("password", "") ?: ""; set(v) = put("password", v)
    var allowWrite: Boolean get() = sp.getBoolean("allowWrite", false); set(v) = sp.edit().putBoolean("allowWrite", v).apply()
    var lastSeq: Long get() = sp.getLong("lastSeq", 0L); set(v) = sp.edit().putLong("lastSeq", v).apply()

    // ADB không dây (đã ghép nối)
    var adbHost: String get() = sp.getString("adbHost", "127.0.0.1") ?: "127.0.0.1"; set(v) = put("adbHost", v.trim().ifEmpty { "127.0.0.1" })
    var adbPort: Int get() = sp.getInt("adbPort", 0); set(v) = sp.edit().putInt("adbPort", v).apply()
    var paired: Boolean get() = sp.getBoolean("paired", false); set(v) = sp.edit().putBoolean("paired", v).apply()

    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    val ready: Boolean get() = url.startsWith("http") && key.isNotEmpty() && password.length >= 6

    fun bridge(): SupabaseBridge = SupabaseBridge(url, key)
}
