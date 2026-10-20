package app.nearaudio

import app.nearaudio.transport.Announce
import app.nearaudio.transport.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class AnnounceTest {
    @Test fun roundTrip() {
        val m = Announce.read(ByteArrayInputStream(Announce.encode("0a1b2c3d", 40123, "Friend ♪")))
        assertEquals("0a1b2c3d", m.id)
        assertEquals(40123, m.port)
        assertEquals("Friend ♪", m.alias)
    }

    @Test fun rejectsTruncationBadMagicAndBadInput() {
        val ok = Announce.encode("0a1b2c3d", 4000, "abc")
        for (n in 0 until ok.size) expectFail { Announce.read(ByteArrayInputStream(ok.copyOf(n))) }
        expectFail { Announce.read(ByteArrayInputStream(ByteArray(20))) }
        expectFail { Announce.encode("xyz", 4000, "a") }
        expectFail { Announce.encode("0a1b2c3d", 0, "a") }
        expectFail { Announce.encode("0a1b2c3d", 4000, "x".repeat(40)) }
    }

    private fun expectFail(block: () -> Unit) {
        try { block(); fail("accepted invalid input") } catch (e: ProtocolException) { /* expected */ }
    }
}
