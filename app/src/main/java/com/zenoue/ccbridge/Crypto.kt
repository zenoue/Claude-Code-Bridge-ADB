package com.zenoue.ccbridge

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ký/kiểm chữ ký lệnh bằng mật khẩu chung (HMAC‑SHA256).
 * Claude Code phải biết đúng mật khẩu thì chữ ký mới khớp -> app mới chạy lệnh.
 */
object Crypto {

    fun hmacHex(password: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** So sánh không phụ thuộc thời gian (chống dò chữ ký). */
    fun constEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    /**
     * Chuỗi được ký, cố định thứ tự trường để hai bên tính giống nhau.
     * Đổi bất kỳ trường nào -> chữ ký đổi -> lệnh bị từ chối.
     */
    fun payload(seq: Long, ts: Long, type: String, cmd: String, nonce: String): String =
        "$seq\n$ts\n$type\n$nonce\n$cmd"

    fun sign(password: String, seq: Long, ts: Long, type: String, cmd: String, nonce: String): String =
        hmacHex(password, payload(seq, ts, type, cmd, nonce))

    fun verify(password: String, seq: Long, ts: Long, type: String, cmd: String, nonce: String, sig: String): Boolean =
        constEq(sign(password, seq, ts, type, cmd, nonce), sig.lowercase())
}
