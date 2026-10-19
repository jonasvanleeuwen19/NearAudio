package app.nearaudio

import app.nearaudio.transport.Codec
import app.nearaudio.transport.Message
import app.nearaudio.transport.Protocol
import app.nearaudio.transport.ProtocolException
import app.nearaudio.transport.RejectReason
import app.nearaudio.transport.sanitizeAlias
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

class ProtocolTest {
    private fun roundTrip(m: Message): Message = Codec.read(ByteArrayInputStream(Codec.encode(m)))
    private val sid = ByteArray(16) { it.toByte() }
    private val commit = ByteArray(32) { (it + 1).toByte() }

    @Test fun helloRoundTrip() {
        val r = roundTrip(Message.Hello(1, sid, "Fiets ♪", commit)) as Message.Hello
        assertEquals(1, r.version)
        assertArrayEquals(sid, r.sessionId)
        assertEquals("Fiets ♪", r.alias)
        assertArrayEquals(commit, r.commitment)
    }

    @Test fun replyRevealAcceptRoundTrip() {
        val key = ByteArray(91) { it.toByte() }
        val reply = roundTrip(Message.HelloReply(1, "B", key)) as Message.HelloReply
        assertEquals("B", reply.alias)
        assertArrayEquals(key, reply.publicKey)
        assertArrayEquals(key, (roundTrip(Message.KeyReveal(key)) as Message.KeyReveal).publicKey)
        val tag = ByteArray(16) { 7 }
        assertArrayEquals(tag, (roundTrip(Message.Accept(tag)) as Message.Accept).tag)
    }

    @Test fun rejectAudioStopPing() {
        assertEquals(RejectReason.BUSY, (roundTrip(Message.Reject(RejectReason.BUSY)) as Message.Reject).reason)
        val ct = ByteArray(3856) { it.toByte() }
        val a = roundTrip(Message.Audio(123456789012L, ct)) as Message.Audio
        assertEquals(123456789012L, a.counter)
        assertArrayEquals(ct, a.ciphertext)
        assertTrue(roundTrip(Message.Stop) is Message.Stop)
        assertTrue(roundTrip(Message.Ping) is Message.Ping)
    }

    @Test fun unknownRejectCodeMapsToUnknown() {
        val bytes = byteArrayOf(5, 0, 0, 0, 1, 42)
        assertEquals(RejectReason.UNKNOWN, (Codec.read(ByteArrayInputStream(bytes)) as Message.Reject).reason)
    }

    @Test fun everyTruncationIsRejected() {
        val full = Codec.encode(Message.Hello(1, sid, "abc", commit))
        for (n in 1 until full.size) {
            try {
                Codec.read(ByteArrayInputStream(full.copyOf(n)))
                fail("accepted truncation at $n")
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }

    @Test fun emptyStreamIsCleanEof() {
        try {
            Codec.read(ByteArrayInputStream(ByteArray(0)))
            fail()
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test fun oversizedLengthRejectedWithoutAllocating() {
        val bytes = ByteBuffer.allocate(5).put(6).putInt(Protocol.MAX_PAYLOAD + 1).array()
        assertRejected(bytes)
        val negative = ByteBuffer.allocate(5).put(6).putInt(-1).array()
        assertRejected(negative)
    }

    @Test fun unknownTypeAndTrailingBytesRejected() {
        assertRejected(byteArrayOf(99, 0, 0, 0, 0))
        assertRejected(byteArrayOf(7, 0, 0, 0, 1, 0)) // Stop with payload
    }

    @Test fun malformedFieldsRejected() {
        // alias length byte says 40 (> max 32)
        val payload = ByteBuffer.allocate(1 + 16 + 1 + 40 + 32).put(1).put(sid).put(40.toByte()).array()
        assertRejected(ByteBuffer.allocate(5 + payload.size).put(1).putInt(payload.size).put(payload).array())
        // audio payload shorter than counter + GCM tag
        assertRejected(ByteBuffer.allocate(5 + 10).put(6).putInt(10).put(ByteArray(10)).array())
    }

    @Test fun encodeValidatesInputs() {
        assertEncodeFails(Message.Hello(1, ByteArray(15), "a", commit))
        assertEncodeFails(Message.Hello(1, sid, "x".repeat(33), commit))
        assertEncodeFails(Message.HelloReply(1, "a", ByteArray(0)))
        assertEncodeFails(Message.HelloReply(1, "a", ByteArray(129)))
        assertEncodeFails(Message.Accept(ByteArray(3)))
        assertEncodeFails(Message.Audio(0, ByteArray(4)))
        assertEncodeFails(Message.Audio(0, ByteArray(Protocol.MAX_PAYLOAD)))
    }

    @Test fun readsConsecutiveMessages() {
        val bytes = Codec.encode(Message.Ping) + Codec.encode(Message.Stop)
        val input = ByteArrayInputStream(bytes)
        assertTrue(Codec.read(input) is Message.Ping)
        assertTrue(Codec.read(input) is Message.Stop)
    }

    @Test fun aliasSanitising() {
        assertEquals("Unknown", sanitizeAlias(null))
        assertEquals("Unknown", sanitizeAlias("  \u0000\n "))
        assertEquals("ab", sanitizeAlias("a\u0007b"))
        assertEquals(Protocol.MAX_ALIAS_CHARS, sanitizeAlias("x".repeat(100)).length)
    }

    private fun assertRejected(bytes: ByteArray) {
        try {
            Codec.read(ByteArrayInputStream(bytes))
            fail("accepted malformed input")
        } catch (e: ProtocolException) {
            // expected
        }
    }

    private fun assertEncodeFails(m: Message) {
        try {
            Codec.encode(m)
            fail("encoded invalid message")
        } catch (e: IOException) {
            assertTrue(e is ProtocolException)
        }
    }
}
