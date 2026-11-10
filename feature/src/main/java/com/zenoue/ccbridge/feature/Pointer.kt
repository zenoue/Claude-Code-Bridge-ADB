package com.zenoue.ccbridge.feature

import org.json.JSONObject

/**
 * Fixed address for a tunnel whose URL changes.
 *
 * localhost.run's free tunnels get a new random domain on every reconnect (and every few hours).
 * Instead of asking the user to copy the new URL each time, the app publishes the current tunnel
 * URL to a fixed, secret rendezvous topic (ntfy.sh/<bridge id>). Claude Code only needs the bridge
 * id + password: `tools/bridge.py` reads the latest record, verifies its HMAC and follows it.
 *
 * Record: {"v":1,"url":"https://xxxx.lhr.life","ts":<ms>,"sig":HMAC(password, "ptr\n<ts>\n<url>")}
 * The record is signed, so someone who learns the topic cannot redirect the client; commands are
 * signed separately, so knowing the tunnel URL is not enough to run anything.
 */
class Pointer(
    private val base: String,
    private val id: () -> String,
    private val password: () -> String,
    private val listener: (State) -> Unit,
) {
    /** [ok] null = nothing published yet. [message] is shown in the UI. */
    data class State(val ok: Boolean?, val url: String?, val at: Long, val message: String)

    private val lock = Object()
    @Volatile private var alive = false
    private var wanted: String? = null
    private var published: String? = null
    private var publishedId: String? = null
    private var publishedAt = 0L
    private var worker: Thread? = null
    @Volatile var state = State(null, null, 0, "Chưa đăng địa chỉ"); private set

    fun start() {
        if (alive) return
        alive = true
        worker = Thread { loop() }.apply { isDaemon = true; name = "pointer"; start() }
    }

    fun stop() {
        alive = false
        synchronized(lock) { lock.notifyAll() }
        worker?.interrupt()
    }

    /** Current tunnel URL (null while the tunnel is down). Publishing happens in the background. */
    fun set(url: String?) {
        synchronized(lock) { wanted = url; lock.notifyAll() }
    }

    /** Publish again soon (bridge id or password changed). */
    fun refresh() {
        synchronized(lock) { published = null; lock.notifyAll() }
    }

    private fun loop() {
        var backoff = MIN_BACKOFF
        while (alive) {
            val url: String?
            val due: Boolean
            synchronized(lock) {
                url = wanted
                due = url != null && (url != published || id() != publishedId ||
                    System.currentTimeMillis() - publishedAt > REFRESH_MS)
                if (!due) try { lock.wait(60_000) } catch (_: InterruptedException) { }
            }
            if (!due || url == null || !alive) continue
            val topic = id()
            try {
                val now = System.currentTimeMillis()
                val r = Net.request(
                    "$base/$topic", "POST", encode(password(), url, now).toByteArray(Charsets.UTF_8),
                    // no push notifications; this is only a mailbox
                    mapOf("Firebase" to "no", "Priority" to "min"),
                )
                if (r.code !in 200..299) throw IllegalStateException("HTTP ${r.code} ${r.body.take(120)}")
                synchronized(lock) { published = url; publishedId = topic; publishedAt = now }
                backoff = MIN_BACKOFF
                emit(State(true, url, now, "Địa chỉ cố định đã trỏ tới hầm hiện tại"))
            } catch (t: Throwable) {
                emit(State(false, url, System.currentTimeMillis(), "Chưa đăng được địa chỉ cố định: ${Net.describe(t)}"))
                synchronized(lock) { try { lock.wait(backoff) } catch (_: InterruptedException) { } }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
        }
    }

    private fun emit(s: State) {
        state = s
        try { listener(s) } catch (_: Throwable) { }
    }

    companion object {
        const val DEFAULT_BASE = "https://ntfy.sh"
        /** ntfy.sh keeps messages for about 12 hours; republish well before that. */
        private const val REFRESH_MS = 3 * 3600_000L
        private const val MIN_BACKOFF = 5_000L
        private const val MAX_BACKOFF = 5 * 60_000L

        fun encode(password: String, url: String, ts: Long): String =
            JSONObject().put("v", 1).put("url", url).put("ts", ts)
                .put("sig", Crypto.signPointer(password, ts, url)).toString()

        /** Newest valid record in an ntfy JSON stream (one event per line); null if none verifies. */
        fun latest(password: String, ndjson: String): String? {
            var bestTs = Long.MIN_VALUE
            var best: String? = null
            for (line in ndjson.lineSequence()) {
                val rec = try {
                    val ev = JSONObject(line)
                    if (ev.optString("event") != "message") continue
                    JSONObject(ev.optString("message"))
                } catch (_: Exception) { continue }
                val url = rec.optString("url"); val ts = rec.optLong("ts")
                if (url.isEmpty() || !Crypto.constEq(Crypto.signPointer(password, ts, url), rec.optString("sig"))) continue
                if (ts > bestTs) { bestTs = ts; best = url }
            }
            return best
        }
    }
}
