package com.zenoue.ccbridge.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Real client against real server code, no network: tools/bridge.py (Python) talks to [HttpApi] behind
 * [LocalServer], and finds it through a fixed address that [Pointer] published to a fake ntfy server.
 * Proves that the Kotlin and Python sides agree on signatures, the pointer record and failover.
 * Skipped when python3 or tools/bridge.py is not available.
 */
class EndToEndTest {
    private val pw = "e2e-password-1234"
    private val id = "ccb-e2etest0000000000000000"

    private val bridgePy: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "tools/bridge.py") }.firstOrNull { it.exists() }

    private fun hasPython() = try {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    } catch (_: Exception) { false }

    /** Fake ntfy: POST /<topic> stores a message, GET /<topic>/json?poll=1 returns them as NDJSON. */
    private class FakeNtfy(port: Int) {
        val messages = CopyOnWriteArrayList<Pair<String, String>>()
        val server = LocalServer(port) { r ->
            val path = r.path.substringBefore('?').trim('/')
            when {
                r.method == "POST" -> { messages.add(path to r.body); LocalServer.Response(200, "{}") }
                r.method == "GET" && path.endsWith("/json") -> {
                    val topic = path.removeSuffix("/json")
                    val lines = messages.filter { it.first == topic }.mapIndexed { i, m ->
                        "{\"id\":\"m$i\",\"time\":${1700000000 + i},\"event\":\"message\",\"topic\":\"$topic\",\"message\":${LocalServer.jsonStr(m.second)}}"
                    }
                    LocalServer.Response(200, lines.joinToString("") { it + "\n" }, "application/x-ndjson")
                }
                else -> LocalServer.Response(404, "{}")
            }
        }
    }

    private class Run(val exit: Int, val out: String, val err: String)

    private fun bridge(env: Map<String, String>, vararg args: String): Run {
        val pb = ProcessBuilder(listOf("python3", bridgePy!!.absolutePath) + args)
        pb.environment().apply {
            keys.filter { it.startsWith("BRIDGE_") }.forEach { remove(it) }
            putAll(env)
            put("PYTHONIOENCODING", "utf-8")
        }
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("bridge.py timed out", p.waitFor(60, TimeUnit.SECONDS))
        return Run(p.exitValue(), out, err)
    }

    @Test fun pythonClientFindsAndCallsTheBridge() {
        assumeTrue(bridgePy != null && hasPython())
        val appPort = 18771; val ntfyPort = 18772; val deadPort = 18773
        val ran = CopyOnWriteArrayList<String>()
        val api = HttpApi({ pw }, { type, cmd, _ ->
            ran.add("$type:$cmd")
            when (type) {
                "ping" -> HttpApi.Reply(0, "pong")
                "cdp" -> {
                    val o = org.json.JSONObject(cmd)
                    when (o.optString("op")) {
                        "read" -> Cdp.chunk(o.optLong("offset", -1))
                        // long enough to need three parts
                        "http" -> Cdp.first("[{\"type\":\"page\",\"id\":\"AB12\",\"title\":\"${"t".repeat(2 * Cdp.CHUNK)}\"," +
                            "\"url\":\"https://example.com/\",\"webSocketDebuggerUrl\":\"ws://localhost/devtools/page/AB12\"}]")
                        else -> Cdp.first("{\"id\":1,\"result\":{\"result\":{\"type\":\"string\",\"value\":\"${o.optString("path")} ${o.optString("cdp")}\"}}}")
                    }
                }
                else -> when (cmd) {
                    "fail" -> HttpApi.Reply(7, "nope")
                    "unicode" -> HttpApi.Reply(0, "Tiếng Việt ✓")
                    "big" -> HttpApi.Reply(0, (1..60000).joinToString("\n"))
                    else -> HttpApi.Reply(0, "ran <$cmd>")
                }
            }
        })
        val app = LocalServer(appPort, api::handle).also { it.start() }
        val ntfy = FakeNtfy(ntfyPort).also { it.server.start() }
        val cache = File.createTempFile("ccb-cache", "").apply { delete(); mkdirs() }
        val states = CopyOnWriteArrayList<Pointer.State>()
        val pointer = Pointer("http://127.0.0.1:$ntfyPort", { id }, { pw }) { states.add(it) }
        try {
            // the app publishes an old (dead) URL first, then the live one
            pointer.start()
            pointer.set("http://127.0.0.1:$deadPort")
            waitFor { ntfy.messages.size == 1 }
            pointer.set("http://127.0.0.1:$appPort")
            waitFor { ntfy.messages.size == 2 }
            assertTrue(states.last().ok == true)
            // someone who knows the topic but not the password cannot redirect the client
            ntfy.messages.add(id to Pointer.encode("attacker-password", "http://127.0.0.1:$deadPort", Long.MAX_VALUE / 2))

            val env = mapOf(
                "BRIDGE_ID" to id, "BRIDGE_PASSWORD" to pw,
                "BRIDGE_RENDEZVOUS" to "http://127.0.0.1:$ntfyPort", "XDG_CACHE_HOME" to cache.absolutePath,
            )
            val ping = bridge(env, "ping")
            assertEquals(ping.err, 0, ping.exit)
            assertEquals("pong", ping.out.trim())

            // (arguments stay ASCII: the JVM would mangle non-ASCII argv under a C locale)
            val exec = bridge(env, "exec", "echo 'a b' \"x\" | tr a-z A-Z")
            assertEquals(exec.err, 0, exec.exit)
            assertEquals("ran <echo 'a b' \"x\" | tr a-z A-Z>", exec.out.trim())
            assertEquals("Tiếng Việt ✓", bridge(env, "exec", "unicode").out.trim())
            assertEquals(7, bridge(env, "exec", "fail").exit)
            // large reply goes through gzip
            val big = bridge(env, "exec", "big")
            assertEquals(big.err, 0, big.exit)
            assertEquals((1..60000).joinToString("\n"), big.out.trimEnd())

            // Chrome DevTools: a reply larger than one chunk is fetched in parts and joined by the client
            val tabs = bridge(env, "chrome", "tabs")
            assertEquals(tabs.err, 0, tabs.exit)
            assertEquals("1\tAB12\t${"t".repeat(2 * Cdp.CHUNK)}\thttps://example.com/", tabs.out.trim())
            val eval = bridge(env, "chrome", "eval", "1", "document.title")
            assertEquals(eval.err, 0, eval.exit)
            assertEquals("/devtools/page/AB12 Runtime.evaluate", eval.out.trim())

            val before = ran.size
            val bad = bridge(env + ("BRIDGE_PASSWORD" to "wrong-password-000"), "exec", "id")
            assertEquals(1, bad.exit)
            assertEquals(before, ran.size)

            // wrong password with a direct URL: the app itself refuses (-2)
            val direct = mapOf("BRIDGE_URL" to "http://127.0.0.1:$appPort", "BRIDGE_PASSWORD" to "wrong-password-000")
            val refused = bridge(direct, "exec", "id")
            assertEquals(1, refused.exit)
            assertTrue(refused.err, refused.err.contains("-2"))
            assertEquals(before, ran.size)

            // stale cache: the cached URL is dead -> the client looks the address up again
            File(cache, "cc-bridge-adb/$id.url").writeText("http://127.0.0.1:$deadPort")
            val again = bridge(env, "exec", "after-move")
            assertEquals(again.err, 0, again.exit)
            assertEquals("http://127.0.0.1:$appPort", File(cache, "cc-bridge-adb/$id.url").readText())
        } finally {
            pointer.stop(); app.stop(); ntfy.server.stop(); cache.deleteRecursively()
        }
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!cond()) {
            assertTrue("timed out waiting", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }
}
