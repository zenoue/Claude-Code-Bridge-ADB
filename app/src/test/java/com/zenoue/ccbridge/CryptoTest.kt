package com.zenoue.ccbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    }

    @Test fun wrongPasswordFails() {
        val sig = Crypto.sign("s3cret", 5, 1700000000000, "exec", "dumpsys battery", "n1")
        assertFalse(Crypto.verify("other", 5, 1700000000000, "exec", "dumpsys battery", "n1", sig))
    }

    @Test fun tamperedFieldsFail() {
        val sig = Crypto.sign("s3cret", 5, 1700000000000, "exec", "dumpsys battery", "n1")
        assertFalse(Crypto.verify("s3cret", 6, 1700000000000, "exec", "dumpsys battery", "n1", sig))
        assertFalse(Crypto.verify("s3cret", 5, 1700000000000, "exec", "rm -rf /", "n1", sig))
    }

    @Test fun policyReadonly() {
        assertTrue(Policy.allowed("dumpsys battery"))
        assertTrue(Policy.allowed("logcat -d | grep Error"))
        assertFalse(Policy.allowed("pm uninstall com.foo"))
        assertFalse(Policy.allowed("echo hi > /sdcard/x"))
        assertFalse(Policy.allowed("reboot"))
    }

    @Test fun constEq() {
        assertTrue(Crypto.constEq("abc", "abc"))
        assertFalse(Crypto.constEq("abc", "abd"))
        assertFalse(Crypto.constEq("abc", "ab"))
    }
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

    @Test fun wrongPasswordRejected() = assertEquals(401, req("ls", password = "otherpassword").code)

    @Test fun replayRejected() {
        assertEquals(200, req("ls", nonce = "fixednonce1").code)
        assertEquals(409, req("ls", nonce = "fixednonce1").code)
    }

    @Test fun staleRejected() = assertEquals(401, req("ls", ts = clock - 10 * 60_000).code)

    @Test fun healthAndNotFound() {
        assertEquals(200, api.handle(LocalServer.Request("GET", "/", emptyMap(), "")).code)
        assertEquals(404, api.handle(LocalServer.Request("GET", "/x", emptyMap(), "")).code)
    }

    @Test fun jsonEscaping() = assertEquals("\"a\\\"b\\n\\u0001\"", LocalServer.jsonStr("a\"b\n\u0001"))
}

class TunnelUrlTest {
    @Test fun localhostRunUrl() {
        val out = "Connect to https://admin.localhost.run/ for docs\nabc123def.lhr.life tunneled with tls termination, https://abc123def.lhr.life\n"
        assertEquals("https://abc123def.lhr.life", Tunnel.findUrl(out, Tunnel.Provider.LOCALHOST_RUN))
    }

    @Test fun serveoUrl() {
        val out = "Forwarding HTTP traffic from https://9f2c1a.serveousercontent.com\n"
        assertEquals("https://9f2c1a.serveousercontent.com", Tunnel.findUrl(out, Tunnel.Provider.SERVEO))
    }

    @Test fun noUrl() = assertEquals(null, Tunnel.findUrl("welcome to https://localhost.run", Tunnel.Provider.LOCALHOST_RUN))

    @Test fun newlineCannotSmuggleCommands() = assertFalse(Policy.allowed("ls\ntouch /sdcard/x"))
}
