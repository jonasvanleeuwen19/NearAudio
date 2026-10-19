package app.nearaudio

import app.nearaudio.session.RecipientLink
import app.nearaudio.transport.Connection
import app.nearaudio.transport.FrameCipher
import app.nearaudio.transport.SessionCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

class RecipientLinkTest {
    /** Connection whose writes block until released: simulates a recipient that stopped reading. */
    private class BlockedConnection : Connection {
        val release = CountDownLatch(1)
        override val input: InputStream = object : InputStream() {
            override fun read(): Int { release.await(); return -1 }
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) { release.await() }
            override fun write(b: ByteArray, off: Int, len: Int) { release.await() }
        }
        override fun setReadTimeout(ms: Int) {}
        override fun close() { release.countDown() }
    }

    @Test fun offerNeverBlocksEvenWhenRecipientIsStuck() {
        val s = SessionCrypto.newKeyPair()
        val r = SessionCrypto.newKeyPair()
        val sid = ByteArray(16)
        val keys = SessionCrypto.derive(s.private, r.public.encoded, s.public.encoded, r.public.encoded, sid)
        val conn = BlockedConnection()
        val link = RecipientLink(conn, FrameCipher(keys.key, sid), queueFrames = 4)
        val job = Thread { runBlocking { try { link.run() } catch (e: java.io.IOException) { /* stall -> closed */ } } }
        job.start()
        val start = System.currentTimeMillis()
        repeat(10_000) { link.offer(ByteArray(3840)) } // would hang/OOM without bounded drop-oldest queue
        assertTrue("offer blocked", System.currentTimeMillis() - start < 2000)
        conn.close()
        job.join(8000)
        assertTrue(!job.isAlive)
    }
}
