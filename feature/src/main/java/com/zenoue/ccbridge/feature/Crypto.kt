package com.zenoue.ccbridge.feature

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing with the shared password (HMAC-SHA256).
 * The password never travels over the network: Claude Code must know it to produce a matching
 * signature, otherwise the app refuses to run the command.
 */
object Crypto {

    fun hmacHex(password: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison (no timing oracle on the signature). */
    fun constEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    /** Signed string for a command; field order is fixed so both sides compute the same value. */
    fun payload(seq: Long, ts: Long, type: String, cmd: String, nonce: String): String =
        "$seq\n$ts\n$type\n$nonce\n$cmd"

    fun sign(password: String, seq: Long, ts: Long, type: String, cmd: String, nonce: String): String =
        hmacHex(password, payload(seq, ts, type, cmd, nonce))

    fun verify(password: String, seq: Long, ts: Long, type: String, cmd: String, nonce: String, sig: String): Boolean =
        constEq(sign(password, seq, ts, type, cmd, nonce), sig.lowercase())

    /** Signature of a fixed-address pointer record (see [Pointer]). */
    fun signPointer(password: String, ts: Long, url: String): String = hmacHex(password, "ptr\n$ts\n$url")
}
