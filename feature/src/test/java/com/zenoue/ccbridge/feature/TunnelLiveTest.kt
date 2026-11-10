package com.zenoue.ccbridge.feature

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Runs the real [Tunnel] against tools/mock_lhr.py; skipped unless MOCK_SSH_PORT is set. */
class TunnelLiveTest {
    @Test fun againstMock() {
        val port = System.getenv("MOCK_SSH_PORT")?.toInt() ?: return
        val api = HttpApi({ "testpassword12" }, { _, _, _ -> HttpApi.Reply(0, "x") })
        val srv = LocalServer(18765, api::handle).also { it.start() }
        val states = CopyOnWriteArrayList<Tunnel.State>()
        val keyFile = File.createTempFile("tunkey", ""); keyFile.delete()
        val t = Tunnel(18765, keyFile, "127.0.0.1", port) { states.add(it); println("STATE $it") }
        t.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && states.none { it.phase == Tunnel.Phase.ONLINE }) Thread.sleep(200)
        Thread.sleep(3000)
        t.stop(); srv.stop()
        assertTrue(states.any { it.phase == Tunnel.Phase.ONLINE && it.url == "https://abc123.lhr.life" })
    }
}
