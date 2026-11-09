package com.zenoue.ccbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cầu nối qua Supabase (bảng `mailbox` dùng REST tự sinh của PostgREST).
 *
 * Hàng lệnh (Claude ghi):  dir='cmd', seq, ts, type, cmd, nonce, sig
 * Hàng kết quả (app ghi):  dir='res', seq, part, parts, exit, output
 * Hàng log (app ghi):      dir='log', output(=text)
 *
 * App đọc lệnh mới bằng cách lọc dir=cmd & seq>lastSeq; ghi kết quả/log bằng INSERT.
 */
class SupabaseBridge(private val baseUrl: String, private val key: String) {

    data class Cmd(
        val seq: Long, val ts: Long, val type: String,
        val cmd: String, val nonce: String, val sig: String,
    )

    private fun rest(path: String) = "$baseUrl/rest/v1/$path"

    private fun open(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("apikey", key)
        // Key mới (sb_publishable_/sb_secret_) không phải JWT -> chỉ gửi qua header apikey.
        if (key.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "AdbBridge")
        c.connectTimeout = 15000; c.readTimeout = 30000
        return c
    }

    private fun body(c: HttpURLConnection): String {
        val s = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: return "")
        return BufferedReader(InputStreamReader(s)).use { it.readText() }
    }

    /** Kiểm tra URL + key + bảng. null = OK. */
    fun check(): String? {
        return try {
            val c = open("GET", rest("mailbox?select=id&limit=1"))
            when (c.responseCode) {
                in 200..299 -> null
                401, 403 -> "Key sai hoặc thiếu quyền (${c.responseCode})"
                404 -> "Chưa có bảng 'mailbox'. Chạy SQL thiết lập trong Supabase."
                else -> "Lỗi ${c.responseCode}: ${body(c).take(160)}"
            }
        } catch (t: Throwable) { "Không kết nối được: ${t.message}" }
    }

    /** Lấy các lệnh mới (seq > lastSeq). */
    fun pollCommands(lastSeq: Long, limit: Int = 20): List<Cmd> {
        val url = rest("mailbox?dir=eq.cmd&seq=gt.$lastSeq&order=seq.asc&limit=$limit&select=seq,ts,type,cmd,nonce,sig")
        val c = open("GET", url)
        if (c.responseCode !in 200..299) { body(c); return emptyList() }
        val arr = try { JSONArray(body(c)) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Cmd>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Cmd(
                o.optLong("seq"), o.optLong("ts"), o.optString("type", "exec"),
                o.optString("cmd", ""), o.optString("nonce", ""), o.optString("sig", "")
            ))
        }
        return out
    }

    fun insertResult(seq: Long, part: Int, parts: Int, exit: Int, output: String): Boolean {
        val row = JSONObject().put("dir", "res").put("seq", seq)
            .put("part", part).put("parts", parts).put("exit", exit)
            .put("output", output).put("ts", System.currentTimeMillis())
        return insert(row)
    }

    fun insertLog(text: String): Boolean {
        val row = JSONObject().put("dir", "log").put("output", text).put("ts", System.currentTimeMillis())
        return insert(row)
    }

    private fun insert(row: JSONObject): Boolean {
        return try {
            val c = open("POST", rest("mailbox"))
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Prefer", "return=minimal")
            c.outputStream.use { it.write(JSONArray().put(row).toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            if (!ok) body(c)
            ok
        } catch (_: Throwable) { false }
    }

    @Suppress("unused")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
