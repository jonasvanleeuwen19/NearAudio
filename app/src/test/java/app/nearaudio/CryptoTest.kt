package app.nearaudio

import app.nearaudio.transport.FrameCipher
import app.nearaudio.transport.ProtocolException
import app.nearaudio.transport.ReplayGuard
import app.nearaudio.transport.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    private val sid = ByteArray(16) { 9 }

    @Test fun bothSidesDeriveSameSecrets() {
        val s = SessionCrypto.newKeyPair()
        val r = SessionCrypto.newKeyPair()
        val sp = s.public.encoded
        val rp = r.public.encoded
        val a = SessionCrypto.derive(s.private, rp, sp, rp, sid)
        val b = SessionCrypto.derive(r.private, sp, sp, rp, sid)
        assertEquals(a.verificationCode, b.verificationCode)
        assertTrue(Regex("\\d{3} \\d{3}").matches(a.verificationCode))
        assertArrayEquals(a.acceptTag, b.acceptTag)
        assertArrayEquals(a.key.encoded, b.key.encoded)
    }

    @Test fun interceptedSessionDoesNotShareSecrets() {
        val s = SessionCrypto.newKeyPair()
        val r = SessionCrypto.newKeyPair()
        val mitm = SessionCrypto.newKeyPair()
        val sp = s.public.encoded
        val rp = r.public.encoded
        val mp = mitm.public.encoded
        val senderView = SessionCrypto.derive(s.private, mp, sp, mp, sid)
        val receiverView = SessionCrypto.derive(r.private, sp, sp, rp, sid)
        assertNotEquals(senderView.acceptTag.toList(), receiverView.acceptTag.toList())
        assertNotEquals(senderView.key.encoded.toList(), receiverView.key.encoded.toList())
    }

    @Test fun invalidPeerKeyRejected() {
        val s = SessionCrypto.newKeyPair()
        try {
            SessionCrypto.derive(s.private, ByteArray(20) { 1 }, s.public.encoded, ByteArray(20), sid)
            fail()
        } catch (e: ProtocolException) {
            // expected
        }
    }

    private fun cipher(): FrameCipher {
        val s = SessionCrypto.newKeyPair()
        val r = SessionCrypto.newKeyPair()
        val keys = SessionCrypto.derive(s.private, r.public.encoded, s.public.encoded, r.public.encoded, sid)
        return FrameCipher(keys.key, sid)
    }

    @Test fun frameRoundTripAndTamperDetection() {
        val c = cipher()
        val plain = ByteArray(3840) { (it % 251).toByte() }
        val sealed = c.seal(5, plain)
        assertEquals(plain.size + 16, sealed.size)
        assertArrayEquals(plain, c.open(5, sealed))
        val tampered = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        try { c.open(5, tampered); fail() } catch (e: ProtocolException) { /* expected */ }
        try { c.open(6, sealed); fail() } catch (e: ProtocolException) { /* wrong counter/nonce */ }
    }

    @Test fun replayGuardOnlyAcceptsIncreasingCounters() {
        val g = ReplayGuard()
        assertTrue(g.accept(0))
        assertTrue(g.accept(1))
        assertFalse(g.accept(1))
        assertFalse(g.accept(0))
        assertTrue(g.accept(10))
        assertFalse(g.accept(-5))
    }
}
