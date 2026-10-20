package app.nearaudio.session

import app.nearaudio.audio.AudioFormatId
import app.nearaudio.audio.FrameCodecs
import app.nearaudio.audio.AudioSink
import app.nearaudio.transport.Codec
import app.nearaudio.transport.Connection
import app.nearaudio.transport.FrameCipher
import app.nearaudio.transport.Message
import app.nearaudio.transport.Protocol
import app.nearaudio.transport.ProtocolException
import app.nearaudio.transport.RejectReason
import app.nearaudio.transport.ReplayGuard
import app.nearaudio.transport.SessionCrypto
import app.nearaudio.transport.sanitizeAlias
import java.io.IOException
import java.security.MessageDigest

class IncomingRequest(val alias: String, val code: String)

/** Receiver side of one incoming session. Blocking; run on an IO thread. Android-free for testing. */
class ReceiverSession(
    private val alias: () -> String,
    private val sinkFor: (AudioFormatId) -> AudioSink,
) {
    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5000
        const val STREAM_TIMEOUT_MS = 8000 // sender sends a frame or keepalive at least every 2 s
    }

    /**
     * [decide] blocks until the user accepts or declines. [onPlaying] is called with false once the
     * session is accepted (waiting for audio) and with true on the first audio frame.
     * Returns normally when declined or when the sender stops; throws [IOException] on failure.
     * [sink] is always stopped before returning.
     */
    fun handle(conn: Connection, decide: (IncomingRequest) -> Boolean, onPlaying: (Boolean) -> Unit) {
        conn.setReadTimeout(HANDSHAKE_TIMEOUT_MS)
        val hello = Codec.read(conn.input) as? Message.Hello ?: throw ProtocolException("expected hello")
        if (hello.version != Protocol.VERSION) {
            Codec.write(conn.output, Message.Reject(RejectReason.UNSUPPORTED_VERSION))
            throw ProtocolException("unsupported version ${hello.version}")
        }
        val format = AudioFormatId.fromId(hello.format)
        if (format == null) {
            Codec.write(conn.output, Message.Reject(RejectReason.UNSUPPORTED_VERSION))
            throw ProtocolException("unsupported audio format ${hello.format}")
        }
        val pair = SessionCrypto.newKeyPair()
        val pub = pair.public.encoded
        Codec.write(conn.output, Message.HelloReply(Protocol.VERSION, alias().take(Protocol.MAX_ALIAS_CHARS), pub))
        val reveal = Codec.read(conn.input) as? Message.KeyReveal ?: throw ProtocolException("expected key reveal")
        if (!MessageDigest.isEqual(SessionCrypto.sha256(reveal.publicKey), hello.commitment)) {
            throw ProtocolException("key commitment mismatch")
        }
        val keys = SessionCrypto.derive(pair.private, reveal.publicKey, reveal.publicKey, pub, hello.sessionId)

        if (!decide(IncomingRequest(sanitizeAlias(hello.alias), keys.verificationCode))) {
            Codec.write(conn.output, Message.Reject(RejectReason.DECLINED))
            return
        }
        Codec.write(conn.output, Message.Accept(keys.acceptTag))
        conn.setReadTimeout(STREAM_TIMEOUT_MS)
        onPlaying(false)

        val cipher = FrameCipher(keys.key, hello.sessionId)
        val decoder = FrameCodecs.decoder(format)
        val sink = sinkFor(format)
        val guard = ReplayGuard()
        var playing = false
        sink.start()
        try {
            while (true) {
                when (val m = Codec.read(conn.input)) {
                    is Message.Audio -> {
                        val plain = cipher.open(m.counter, m.ciphertext)
                        if (!guard.accept(m.counter)) continue
                        val pcm = decoder.decode(plain)
                        if (pcm.isEmpty() || pcm.size > format.frameBytes || pcm.size % (2 * format.channels) != 0) {
                            throw ProtocolException("bad audio frame size ${pcm.size}")
                        }
                        if (!playing) {
                            playing = true
                            onPlaying(true)
                        }
                        sink.play(pcm)
                    }
                    is Message.Ping -> Unit
                    is Message.Stop -> return
                    else -> throw ProtocolException("unexpected message during streaming")
                }
            }
        } finally {
            sink.stop()
        }
    }
}
