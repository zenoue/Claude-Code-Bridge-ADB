package com.zenoue.ccbridge

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
    private val key = "k3yABCDEFGHJKLMNPQRSTUVW"
    private val api = HttpApi({ key }, { type, cmd, t -> HttpApi.Reply(0, "$type:$cmd:$t") })

    private fun req(method: String, path: String, body: String = "") =
        api.handle(LocalServer.Request(method, path, emptyMap(), body))

    @Test fun commandRunsWithKeyInPath() {
        val r = req("POST", "/$key/cmd", "{\"type\":\"exec\",\"cmd\":\"dumpsys battery\",\"timeout\":5000}")
        assertEquals(200, r.code)
        assertTrue(r.body.contains("exec:dumpsys battery:5000"))
    }

    @Test fun timeoutIsClamped() {
        val r = req("POST", "/$key/cmd", "{\"cmd\":\"ls\",\"timeout\":5}")
        assertTrue(r.body.contains("exec:ls:1000"))
    }

    @Test fun wrongOrMissingKeyIs404() {
        assertEquals(404, req("POST", "/wrongkeywrongkeywrongkey/cmd", "{\"cmd\":\"ls\"}").code)
        assertEquals(404, req("POST", "/cmd", "{\"cmd\":\"ls\"}").code)
        assertEquals(404, req("GET", "/${key.dropLast(1)}/health").code)
    }

    @Test fun emptyKeyNeverMatches() {
        val open = HttpApi({ "" }, { _, _, _ -> HttpApi.Reply(0, "x") })
        assertEquals(404, open.handle(LocalServer.Request("POST", "//cmd", emptyMap(), "{}")).code)
    }

    @Test fun healthAndRoot() {
        assertEquals(200, req("GET", "/").code)
        val h = req("GET", "/$key/health?x=1")
        assertEquals(200, h.code)
        assertTrue(h.body.startsWith(HttpApi.HEALTH_BODY))
        assertEquals(404, req("GET", "/$key/cmd").code)
    }

    @Test fun badJson() = assertEquals(400, req("POST", "/$key/cmd", "not json").code)

    @Test fun constEq() {
        assertTrue(HttpApi.constEq("abc", "abc"))
        assertFalse(HttpApi.constEq("abc", "abd"))
        assertFalse(HttpApi.constEq("abc", "ab"))
    }

    @Test fun jsonEscaping() = assertEquals("\"a\\\"b\\n\\u0001\"", LocalServer.jsonStr("a\"b\n\u0001"))
}

class TunnelParserTest {
    private val lhr = Tunnel.Provider.LOCALHOST_RUN

    @Test fun localhostRunJsonEvent() {
        val out = "{\"event\":\"authn\",\"message\":\"authenticated as anonymous user\"}\n" +
            "{\"event\":\"tcpip-forward\",\"address\":\"abc123def.lhr.life\",\"listen_host\":\"localhost\",\"listen_port\":80}\n"
        assertEquals("https://abc123def.lhr.life", TunnelParser.parse(out, lhr))
    }

    @Test fun localhostRunTextFallback() {
        val out = "Connect to https://admin.localhost.run/ for docs\nabc123def.lhr.life tunneled with tls termination, https://abc123def.lhr.life\n"
        assertEquals("https://abc123def.lhr.life", TunnelParser.parse(out, lhr))
    }

    @Test fun pinggyUrl() {
        val out = "You are not authenticated.\nhttp://rnxyz-1-2-3-4.a.free.pinggy.link\nhttps://rnxyz-1-2-3-4.a.free.pinggy.link\n"
        assertEquals("https://rnxyz-1-2-3-4.a.free.pinggy.link", TunnelParser.parse(out, Tunnel.Provider.PINGGY))
    }

    @Test fun serveoUrl() {
        val out = "Forwarding HTTP traffic from https://9f2c1a.serveousercontent.com\n"
        assertEquals("https://9f2c1a.serveousercontent.com", TunnelParser.parse(out, Tunnel.Provider.SERVEO))
    }

    @Test fun ignoresHomepageAndOtherProviders() {
        assertNull(TunnelParser.parse("welcome to https://localhost.run and https://www.lhr.life", lhr))
        assertNull(TunnelParser.parse("https://abc.serveo.net", lhr))
    }

    @Test fun chainSelection() {
        assertEquals(Tunnel.Provider.ALL, Tunnel.Provider.chain(Tunnel.Provider.AUTO))
        assertEquals(Tunnel.Provider.ALL, Tunnel.Provider.chain("unknown"))
        assertEquals(listOf(Tunnel.Provider.PINGGY), Tunnel.Provider.chain("pinggy"))
    }
}
