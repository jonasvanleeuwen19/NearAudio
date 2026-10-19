package app.nearaudio.transport

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SessionKeys(
    val key: SecretKey,
    /** Six-digit code both users compare ("123 456"). */
    val verificationCode: String,
    /** Proves to the sender that the receiver derived the same secret. */
    val acceptTag: ByteArray,
)

/** Ephemeral ECDH (P-256) + SHA-256 key derivation + AES-128-GCM frames. Only JDK/Android primitives. */
object SessionCrypto {
    fun newKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun derive(
        own: PrivateKey,
        peerPublic: ByteArray,
        senderPublic: ByteArray,
        receiverPublic: ByteArray,
        sessionId: ByteArray,
    ): SessionKeys {
        try {
            val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(own)
            agreement.doPhase(peer, true)
            val secret = agreement.generateSecret()

            fun h(label: String): ByteArray {
                val md = MessageDigest.getInstance("SHA-256")
                for (part in listOf(label.toByteArray(), sessionId, senderPublic, receiverPublic, secret)) {
                    md.update(ByteBuffer.allocate(4).putInt(part.size).array())
                    md.update(part)
                }
                return md.digest()
            }

            val sasValue = (ByteBuffer.wrap(h("sas")).int.toLong() and 0xFFFFFFFFL) % 1_000_000L
            val digits = sasValue.toString().padStart(6, '0')
            return SessionKeys(
                key = SecretKeySpec(h("key").copyOf(16), "AES"),
                verificationCode = digits.substring(0, 3) + " " + digits.substring(3),
                acceptTag = h("accept").copyOf(Protocol.ACCEPT_TAG_BYTES),
            )
        } catch (e: GeneralSecurityException) {
            throw ProtocolException("key agreement failed")
        }
    }
}

/** One direction only (sender -> receiver), so a per-session frame counter is a unique GCM nonce. */
class FrameCipher(private val key: SecretKey, private val sessionId: ByteArray) {
    private fun cipher(mode: Int, counter: Long): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(128, nonce))
        c.updateAAD(sessionId)
        return c
    }

    fun seal(counter: Long, plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, counter).doFinal(plain)

    fun open(counter: Long, data: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, counter).doFinal(data)
    } catch (e: GeneralSecurityException) {
        throw ProtocolException("frame authentication failed")
    }
}

/** Rejects replayed or reordered frames: counters must strictly increase. */
class ReplayGuard {
    private var last = -1L
    fun accept(counter: Long): Boolean {
        if (counter < 0 || counter <= last) return false
        last = counter
        return true
    }
}
