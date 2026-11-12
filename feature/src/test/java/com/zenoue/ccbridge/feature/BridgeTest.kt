package com.zenoue.ccbridge.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test fun policyReadonly() {
        assertTrue(Policy.allowed("dumpsys battery"))
        assertTrue(Policy.allowed("logcat -d | grep Error"))
        assertFalse(Policy.allowed("pm uninstall com.foo"))
        assertFalse(Policy.allowed("echo hi > /sdcard/x"))
        assertFalse(Policy.allowed("reboot"))
    }

    @Test fun newlineCannotSmuggleCommands() = assertFalse(Policy.allowed("ls\ntouch /sdcard/x"))
}

class ExitParseTest {
    @Test fun parsesExitAndStripsMarker() {
        assertEquals(0 to "hello", Adb.splitExit("hello\n__CCB_EXIT__=0\n"))
        assertEquals(127 to "sh: foo: not found", Adb.splitExit("sh: foo: not found\n__CCB_EXIT__=127\n"))
        assertEquals(1 to "", Adb.splitExit("__CCB_EXIT__=1\n"))
    }

    @Test fun noMarkerKeepsOutput() {
        assertEquals(0 to "abc", Adb.splitExit("abc"))
    }
}

class ConsoleTest {
    @Test fun shortOutputUnchanged() = assertEquals("a\nb", Console.clip("a\nb"))

    @Test fun longOutputIsClippedForScreen() {
        val many = (1..5000).joinToString("\n") { "line $it" }
        val c = Console.clip(many)
        assertTrue(c.startsWith("line 1\n"))
        assertTrue(c.endsWith("line 5000"))
        assertTrue(c.contains("ẩn ${5000 - Console.OUT_KEEP_LINES * 2} dòng"))
        assertTrue(c.length < Console.OUT_MAX_CHARS + 200)

        val oneLine = "x".repeat(100_000)
        assertTrue(Console.clip(oneLine).length < Console.OUT_MAX_CHARS + 200)
    }

    @Test fun idsIncreaseAndSinceWorksAfterTrim() {
        Console.clear()
        repeat(Console.MAX + 10) { Console.out("l$it") }
        val snap = Console.snapshot()
        assertEquals(Console.MAX, snap.size)
        val last = snap.last().id
        Console.info("new")
        val fresh = Console.since(last)
        assertEquals(1, fresh.size)
        assertEquals("new", fresh[0].text)
    }
}

class CryptoTest {
    @Test fun hmacKnownVector() {
        // HMAC-SHA256(key="key", msg="The quick brown fox jumps over the lazy dog")
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            Crypto.hmacHex("key", "The quick brown fox jumps over the lazy dog")
        )
    }

    @Test fun signVerifyRoundTrip() {
        val sig = Crypto.sign("s3cret", 5, 1700000000000, "exec", "dumpsys battery", "n1")
        assertTrue(Crypto.verify("s3cret", 5, 1700000000000, "exec", "dumpsys battery", "n1", sig))
        assertTrue(Crypto.verify("s3cret", 5, 1700000000000, "exec", "dumpsys battery", "n1", sig.uppercase()))
    }

    @Test fun wrongPasswordOrTamperedFieldsFail() {
        val sig = Crypto.sign("s3cret", 5, 1700000000000, "exec", "dumpsys battery", "n1")
        assertFalse(Crypto.verify("other", 5, 1700000000000, "exec", "dumpsys battery", "n1", sig))
        assertFalse(Crypto.verify("s3cret", 6, 1700000000000, "exec", "dumpsys battery", "n1", sig))
        assertFalse(Crypto.verify("s3cret", 5, 1700000000000, "exec", "rm -rf /", "n1", sig))
        assertFalse(Crypto.verify("s3cret", 5, 1700000000000, "ping", "dumpsys battery", "n1", sig))
    }

    @Test fun constEq() {
        assertTrue(Crypto.constEq("abc", "abc"))
        assertFalse(Crypto.constEq("abc", "abd"))
        assertFalse(Crypto.constEq("abc", "ab"))
    }
}

class HttpApiTest {
    private val pw = "testpassword12"
    private var clock = 1_800_000_000_000L
    private val api = HttpApi({ pw }, { type, cmd, t -> HttpApi.Reply(0, "$type:$cmd:$t") }, { clock })

    private fun req(cmd: String, nonce: String = "nonce-" + cmd.hashCode(), ts: Long = clock, password: String = pw, timeout: Long? = null): LocalServer.Response {
        val sig = Crypto.sign(password, ts, ts, "exec", cmd, nonce)
        val t = if (timeout != null) ",\"timeout\":$timeout" else ""
        val body = "{\"seq\":$ts,\"ts\":$ts,\"type\":\"exec\",\"cmd\":${LocalServer.jsonStr(cmd)},\"nonce\":\"$nonce\",\"sig\":\"$sig\"$t}"
        return api.handle(LocalServer.Request("POST", "/cmd", emptyMap(), body))
    }

    @Test fun validCommandRuns() {
        val r = req("dumpsys battery", timeout = 5000)
        assertEquals(200, r.code)
        assertTrue(r.body.contains("exec:dumpsys battery:5000"))
    }

    @Test fun timeoutIsClamped() = assertTrue(req("ls", timeout = 5).body.contains("exec:ls:1000"))

    @Test fun wrongPasswordRejected() {
        val r = req("ls", password = "otherpassword")
        assertEquals(401, r.code)
        assertTrue(r.body.contains("\"exit\":-2"))
    }

    @Test fun replayRejected() {
        assertEquals(200, req("ls", nonce = "fixednonce1").code)
        val again = req("ls", nonce = "fixednonce1")
        assertEquals(409, again.code)
        assertTrue(again.body.contains("\"exit\":-6"))
    }

    @Test fun staleRejected() {
        val r = req("ls", ts = clock - 10 * 60_000)
        assertEquals(401, r.code)
        assertTrue(r.body.contains("\"exit\":-5"))
    }

    @Test fun unsignedRejected() =
        assertEquals(401, api.handle(LocalServer.Request("POST", "/cmd", emptyMap(), "{\"cmd\":\"ls\"}")).code)

    @Test fun healthAndNotFound() {
        val h = api.handle(LocalServer.Request("GET", "/health?x=1", emptyMap(), ""))
        assertEquals(200, h.code)
        assertTrue(h.body.startsWith(HttpApi.HEALTH_BODY))
        assertEquals(200, api.handle(LocalServer.Request("GET", "/", emptyMap(), "")).code)
        assertEquals(404, api.handle(LocalServer.Request("GET", "/x", emptyMap(), "")).code)
    }

    @Test fun badJson() = assertEquals(400, api.handle(LocalServer.Request("POST", "/cmd", emptyMap(), "not json")).code)

    @Test fun jsonEscaping() = assertEquals("\"a\\\"b\\n\\u0001\"", LocalServer.jsonStr("a\"b\n\u0001"))
}

class PointerTest {
    private val pw = "testpassword12"
    private fun event(message: String) =
        "{\"id\":\"x\",\"time\":1,\"event\":\"message\",\"topic\":\"t\",\"message\":${LocalServer.jsonStr(message)}}"

    @Test fun newestValidRecordWins() {
        val stream = listOf(
            "{\"id\":\"o\",\"time\":1,\"event\":\"open\",\"topic\":\"t\"}",
            event(Pointer.encode(pw, "https://old.lhr.life", 1000)),
            event(Pointer.encode(pw, "https://new.lhr.life", 3000)),
            event(Pointer.encode(pw, "https://mid.lhr.life", 2000)),
            "garbage", event("not json"),
        ).joinToString("\n")
        assertEquals("https://new.lhr.life", Pointer.latest(pw, stream))
    }

    @Test fun forgedOrTamperedRecordsIgnored() {
        val forged = event(Pointer.encode("attackerpassword", "https://evil.example", 9000))
        val tampered = event(Pointer.encode(pw, "https://good.lhr.life", 5000).replace("good", "evil"))
        val good = event(Pointer.encode(pw, "https://good.lhr.life", 4000))
        assertEquals("https://good.lhr.life", Pointer.latest(pw, "$forged\n$tampered\n$good"))
        assertNull(Pointer.latest(pw, "$forged\n$tampered"))
        assertNull(Pointer.latest(pw, ""))
    }
}

class TunnelParserTest {
    @Test fun localhostRunJsonEvent() {
        val out = "{\"event\":\"authn\",\"message\":\"authenticated as anonymous user\"}\n" +
            "{\"event\":\"tcpip-forward\",\"address\":\"abc123def.lhr.life\",\"listen_host\":\"localhost\",\"listen_port\":80}\n"
        assertEquals("https://abc123def.lhr.life", TunnelParser.parse(out))
    }

    @Test fun localhostRunTextFallback() {
        val out = "Connect to https://admin.localhost.run/ for docs\nabc123def.lhr.life tunneled with tls termination, https://abc123def.lhr.life\n"
        assertEquals("https://abc123def.lhr.life", TunnelParser.parse(out))
    }

    @Test fun ignoresHomepageAndOtherHosts() {
        assertNull(TunnelParser.parse("welcome to https://localhost.run and https://www.lhr.life"))
        assertNull(TunnelParser.parse("https://abc.serveo.net https://x.example.com"))
        assertNull(TunnelParser.parse("{\"address\":\"localhost\"}"))
    }

    @Test fun partialOutputHasNoUrlYet() = assertNull(TunnelParser.parse("{\"event\":\"tcpip-forward\",\"addr"))
}

class ClaudeSetupTest {
    private val block = ClaudeSetup.block("owner/repo", "ccb-abc", "secretsecret", "https://x.lhr.life")

    @Test fun carriesCredentialsAndDownload() {
        assertTrue(block.contains("export BRIDGE_ID='ccb-abc'\n"))
        assertTrue(block.contains("export BRIDGE_PASSWORD='secretsecret'\n"))
        assertTrue(block.contains("export BRIDGE_URL='https://x.lhr.life'\n"))
        assertTrue(block.contains("curl -fsSL https://raw.githubusercontent.com/owner/repo/main_1/tools/bridge.py -o ~/.ccbridge/bridge.py"))
        assertFalse(ClaudeSetup.block("owner/repo", "ccb-abc", "secretsecret", null).contains("BRIDGE_URL="))
    }

    /** Pasted into a plain shell, the explanation must not run as commands. */
    @Test fun everyLineIsACommandOrAComment() {
        for (line in block.lines()) {
            assertTrue(line, line.startsWith("#") || line.startsWith("export ") ||
                line.startsWith("mkdir -p ") || line.startsWith("python3 ~/.ccbridge/bridge.py ping"))
        }
    }
}
