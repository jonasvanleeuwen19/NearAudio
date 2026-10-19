package app.nearaudio

import app.nearaudio.audio.AudioConfig
import app.nearaudio.audio.AudioSink
import app.nearaudio.audio.PcmSource
import app.nearaudio.discovery.Peer
import app.nearaudio.session.ReceiverSession
import app.nearaudio.session.RecipientState
import app.nearaudio.session.RecipientTable
import app.nearaudio.session.SenderSession
import app.nearaudio.transport.Connection
import app.nearaudio.transport.Connector
import app.nearaudio.transport.SocketConnection
import app.nearaudio.transport.TcpConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/** End-to-end protocol/session tests over real loopback TCP sockets (no Android needed). */
class SessionIntegrationTest {
    private class FakeSink : AudioSink {
        val frames: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        @Volatile var started = false
        @Volatile var stopped = false
        override fun start() { started = true }
        override fun play(pcm: ByteArray) { frames.add(pcm) }
        override fun stop() { stopped = true }
    }

    private class FakeSource : PcmSource {
        @Volatile var aborted = false
        @Volatile var closed = false
        private var n = 0
        override fun readFrame(frame: ByteArray) {
            if (aborted) throw IOException("aborted")
            Thread.sleep(5)
            frame.fill(((n++ % 100) + 1).toByte())
        }
        override fun abort() { aborted = true }
        override fun close() { closed = true; aborted = true }
    }

    private class FakeReceiver(val sink: FakeSink, val accept: Boolean, val port: Int, val server: ServerSocket) {
        @Volatile var codeSeen: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<ServerSocket>()

    @Before fun setUp() {}

    @After fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun startReceiver(accept: Boolean): Pair<Peer, FakeReceiver> {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        servers += server
        val sink = FakeSink()
        val rx = FakeReceiver(sink, accept, server.localPort, server)
        Thread {
            try {
                val sock = server.accept()
                val conn = SocketConnection(sock)
                try {
                    ReceiverSession({ "Receiver" }, sink).handle(conn, { rx.codeSeen = it.code; accept }, {})
                } catch (e: IOException) {
                    // sender went away; fine
                } finally {
                    conn.close()
                }
            } catch (e: IOException) {
                // server closed
            }
        }.also { it.isDaemon = true }.start()
        val id = "%08x".format(server.localPort)
        return Peer(id, "Receiver", "127.0.0.1", server.localPort) to rx
    }

    private fun newSender(table: RecipientTable, connector: Connector = TcpConnector) =
        SenderSession(scope, table, connector, { "Sender" })

    private fun waitFor(what: String, timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timeout waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun connect(table: RecipientTable, sender: SenderSession, peer: Peer) {
        table.peerSeen(peer.id, peer.alias)
        assertTrue(table.setSelected(peer.id, true))
        sender.connect(peer)
    }

    @Test fun streamsAudioToRecipientAndStopsCleanly() {
        val table = RecipientTable()
        val sender = newSender(table)
        val (peer, rx) = startReceiver(accept = true)
        connect(table, sender, peer)
        waitFor("connected") { table.get(peer.id)!!.state == RecipientState.CONNECTED }
        assertTrue(table.get(peer.id)!!.verificationCode == rx.codeSeen)

        val source = FakeSource()
        sender.startSharing(source)
        waitFor("audio") { rx.sink.frames.size >= 5 }
        waitFor("receiving state") { table.get(peer.id)!!.state == RecipientState.RECEIVING }
        for (f in rx.sink.frames.toList()) {
            assertEquals(AudioConfig.FRAME_BYTES, f.size)
            assertTrue(f.all { it == f[0] }) // frame contents survived encryption intact
        }

        sender.stopAll()
        waitFor("sink stopped") { rx.sink.stopped }
        waitFor("disconnected") { table.get(peer.id)!!.state == RecipientState.DISCONNECTED }
        assertTrue(rx.sink.started)
        waitFor("source closed") { source.closed }
        assertFalse(sender.isSharing)
    }

    @Test fun declinedSessionFailsWithoutPlayback() {
        val table = RecipientTable()
        val sender = newSender(table)
        val (peer, rx) = startReceiver(accept = false)
        connect(table, sender, peer)
        waitFor("failed") { table.get(peer.id)!!.state == RecipientState.FAILED }
        assertEquals("declined", table.get(peer.id)!!.detail)
        assertFalse(rx.sink.started)
    }

    @Test fun unreachablePeerFailsCleanly() {
        val table = RecipientTable()
        val sender = newSender(table, Connector { _, _, _ -> throw java.net.ConnectException("refused") })
        table.peerSeen("dead0000", "Dead")
        table.setSelected("dead0000", true)
        sender.connect(Peer("dead0000", "Dead", "127.0.0.1", 1))
        waitFor("failed") { table.get("dead0000")!!.state == RecipientState.FAILED }
        assertEquals("unreachable", table.get("dead0000")!!.detail)
    }

    @Test fun recipientLeavingDoesNotAffectOthers() {
        val table = RecipientTable()
        val sender = newSender(table)
        val (p1, rx1) = startReceiver(true)
        val (p2, rx2) = startReceiver(true)
        connect(table, sender, p1)
        connect(table, sender, p2)
        waitFor("both connected") { table.connectedIds().size == 2 }
        val source = FakeSource()
        sender.startSharing(source)
        waitFor("both receiving") { rx1.sink.frames.size >= 3 && rx2.sink.frames.size >= 3 }

        sender.disconnect(p1.id)
        waitFor("p1 disconnected") { table.get(p1.id)!!.state == RecipientState.DISCONNECTED }
        val before = rx2.sink.frames.size
        waitFor("p2 keeps receiving") { rx2.sink.frames.size > before + 3 }
        assertEquals(RecipientState.RECEIVING, table.get(p2.id)!!.state)
        assertTrue(rx1.sink.stopped)
        sender.stopAll()
        waitFor("p2 stopped") { rx2.sink.stopped }
    }

    @Test fun rawGarbageBeforeHandshakeIsRejectedByReceiver() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        servers += server
        val sink = FakeSink()
        var failure: Exception? = null
        val t = Thread {
            val conn: Connection = SocketConnection(server.accept())
            try {
                ReceiverSession({ "R" }, sink).handle(conn, { true }, {})
            } catch (e: IOException) {
                failure = e
            } finally {
                conn.close()
            }
        }
        t.start()
        java.net.Socket("127.0.0.1", server.localPort).use { it.getOutputStream().write(ByteArray(64) { 0x55 }) }
        t.join(8000)
        assertTrue(failure != null)
        assertFalse(sink.started)
    }
}
