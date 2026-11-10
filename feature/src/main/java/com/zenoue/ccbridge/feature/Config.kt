package com.zenoue.ccbridge.feature

import android.content.Context
import java.io.File
import java.security.SecureRandom

/** Settings, stored privately inside the app. */
class Config(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    /** Shared secret used to sign commands. Generated on first use so the bridge works out of the box. */
    var password: String
        get() = synchronized(LOCK) {
            sp.getString("password", null)?.takeIf { it.length >= MIN_PASSWORD }
                ?: random(24).also { sp.edit().putString("password", it).commit() }
        }
        set(v) { synchronized(LOCK) { sp.edit().putString("password", v).commit() } }

    /** Fixed address: the secret rendezvous topic where the current tunnel URL is published. */
    val bridgeId: String
        get() = synchronized(LOCK) { sp.getString("bridgeId", null)?.takeIf { it.length >= 20 } ?: newBridgeId() }

    /** New fixed address; the old one stops being updated. */
    fun newBridgeId(): String = synchronized(LOCK) {
        ("ccb-" + random(24)).also { sp.edit().putString("bridgeId", it).commit() }
    }

    var allowWrite: Boolean get() = sp.getBoolean("allowWrite", false); set(v) = sp.edit().putBoolean("allowWrite", v).apply()

    // Tunnel
    var httpPort: Int get() = sp.getInt("httpPort", 8765); set(v) = sp.edit().putInt("httpPort", v).apply()
    val sshKey: File get() = File(ctx.filesDir, "tunnel_key")

    // Wireless ADB (after pairing)
    var adbHost: String get() = sp.getString("adbHost", "127.0.0.1") ?: "127.0.0.1"; set(v) = put("adbHost", v.trim().ifEmpty { "127.0.0.1" })
    var adbPort: Int get() = sp.getInt("adbPort", 0); set(v) = sp.edit().putInt("adbPort", v).apply()
    var paired: Boolean get() = sp.getBoolean("paired", false); set(v) = sp.edit().putBoolean("paired", v).apply()
    /** Classic TCP port 5555 mode (no Wi-Fi needed) is enabled - the app prefers 127.0.0.1:5555. */
    var tcpMode: Boolean get() = sp.getBoolean("tcpMode", false); set(v) = sp.edit().putBoolean("tcpMode", v).apply()

    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    companion object {
        const val MIN_PASSWORD = 12
        private val LOCK = Any()
        private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        fun random(len: Int): String {
            val r = SecureRandom()
            return (1..len).map { CHARS[r.nextInt(CHARS.length)] }.joinToString("")
        }
    }
}
