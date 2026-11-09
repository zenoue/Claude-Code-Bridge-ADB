package com.zenoue.ccbridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class TunnelLiveTest {
    @Test fun againstMock() {
        val port = System.getenv("MOCK_SSH_PORT")?.toInt() ?: return
        val key = System.getenv("KEY")!!
        val api = HttpApi({ key }, { _, _, _ -> HttpApi.Reply(0, "x") })
        val srv = LocalServer(18765, api::handle).also { it.start() }
        val states = CopyOnWriteArrayList<Tunnel.State>()
        val keyFile = File.createTempFile("tunkey", ""); keyFile.delete()
        val t = Tunnel(18765, keyFile, { "/$key/health" }, "127.0.0.1", port) { states.add(it); println("STATE $it") }
        t.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && states.none { it.phase == Tunnel.Phase.ONLINE }) Thread.sleep(200)
        Thread.sleep(3000)
        t.stop(); srv.stop()
        assertTrue(states.any { it.phase == Tunnel.Phase.ONLINE && it.url == "https://abc123.lhr.life" })
    }
}
