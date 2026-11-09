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
