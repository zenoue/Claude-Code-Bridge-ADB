package com.zenoue.ccbridge.feature

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Minimal HTTP client for the app's own outbound calls (fixed-address pointer, tunnel self-check). */
object Net {
    class Response(val code: Int, val body: String)

    /**
     * The app installs its bundled Conscrypt as the first security provider (needed for ADB pairing),
     * which also makes it the default TLS stack. If a handshake fails with it, retry once with the
     * platform's own TLS provider.
     */
    private val platformFactory: SSLSocketFactory? by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), "AndroidOpenSSL")
            tmf.init(null as java.security.KeyStore?)
            SSLContext.getInstance("TLS", "AndroidOpenSSL").apply { init(null, tmf.trustManagers, null) }.socketFactory
        } catch (_: Throwable) { null }
    }

    fun request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
    ): Response = try {
        once(url, method, body, headers, timeoutMs, null)
    } catch (e: SSLException) {
        val f = platformFactory ?: throw e
        once(url, method, body, headers, timeoutMs, f)
    }

    private fun once(
        url: String, method: String, body: ByteArray?, headers: Map<String, String>,
        timeoutMs: Int, factory: SSLSocketFactory?,
    ): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (factory != null && c is HttpsURLConnection) c.sslSocketFactory = factory
            c.requestMethod = method
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "cc-bridge-adb")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Response(code, text)
        } finally { c.disconnect() }
    }

    /** Short description of a failure for logs. */
    fun describe(t: Throwable): String = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
}
