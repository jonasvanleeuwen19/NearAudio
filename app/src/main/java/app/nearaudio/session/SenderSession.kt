package app.nearaudio.session

import app.nearaudio.audio.AudioConfig
import app.nearaudio.audio.AudioFormatId
import app.nearaudio.audio.FrameCodecs
import app.nearaudio.audio.PcmSource
import app.nearaudio.discovery.Peer
import app.nearaudio.transport.Codec
import app.nearaudio.transport.Connection
import app.nearaudio.transport.Connector
import app.nearaudio.transport.FrameCipher
import app.nearaudio.transport.Message
import app.nearaudio.transport.Protocol
import app.nearaudio.transport.ProtocolException
import app.nearaudio.transport.RejectReason
import app.nearaudio.transport.SessionCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class RejectedException(val reason: RejectReason) : IOException("rejected: $reason")

/** Sender side: connects to recipients, runs capture, fans frames out. Android-free so it is unit-testable. */
class SenderSession(
    private val scope: CoroutineScope,
    private val table: RecipientTable,
    private val connector: Connector,
    private val alias: () -> String,
    private val format: () -> AudioFormatId = { AudioFormatId.PCM_48K_STEREO },
    private val onSilence: (Boolean) -> Unit = {},
    private val onCaptureFailed: (String) -> Unit = {},
) {
    companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val HANDSHAKE_TIMEOUT_MS = 5000
        const val ACCEPT_TIMEOUT_MS = 35_000 // receiver waits 30 s for its user
        const val FORCE_CLOSE_MS = 1000L
        const val SILENCE_FRAMES = 250 // 5 s of all-zero PCM
    }

    private val conns = ConcurrentHashMap<String, Connection>()
    private val links = ConcurrentHashMap<String, RecipientLink>()
    private val userClosed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val random = SecureRandom()

    @Volatile private var source: PcmSource? = null
    private var captureJob: Job? = null

    val isSharing: Boolean get() = source != null

    fun connect(peer: Peer) {
        if (!table.beginConnect(peer.id)) return
        userClosed.remove(peer.id)
        scope.launch(Dispatchers.IO) { runLink(peer) }
    }

    /** Ends one recipient: gracefully if streaming, immediately if still connecting. */
    fun disconnect(id: String) {
        userClosed.add(id)
        val link = links[id]
        if (link != null) link.requestStop() else conns[id]?.let { runCatching { it.close() } }
    }

    fun startSharing(src: PcmSource) {
        check(source == null) { "already sharing" }
        source = src
        captureJob = scope.launch(Dispatchers.IO) { captureLoop(src) }
    }

    /** Stops capture immediately, tells every recipient to stop, and closes all connections. */
    fun stopAll() {
        val s = source
        source = null
        s?.abort()
        table.snapshot().filter { it.isLive }.forEach { userClosed.add(it.peerId) }
        for ((id, c) in conns) if (links[id] == null) runCatching { c.close() }
        links.values.forEach { it.requestStop() }
        // Safety net: a recipient that does not drain its queue must not keep the session alive.
        scope.launch {
            delay(FORCE_CLOSE_MS)
            conns.values.forEach { runCatching { it.close() } }
        }
        onSilence(false)
    }

    private fun captureLoop(src: PcmSource) {
        var silentFrames = 0
        var silent = false
        try {
            while (true) {
                val frame = ByteArray(AudioConfig.FRAME_BYTES)
                src.readFrame(frame)
                for (l in links.values) l.offer(frame)
                if (isSilent(frame)) {
                    silentFrames++
                    if (silentFrames == SILENCE_FRAMES) {
                        silent = true
                        onSilence(true)
                    }
                } else {
                    silentFrames = 0
                    if (silent) {
                        silent = false
                        onSilence(false)
                    }
                }
            }
        } catch (e: IOException) {
            if (source === src) onCaptureFailed(e.message ?: "capture failed") // not a requested stop
        } finally {
            src.close()
        }
    }

    private fun isSilent(frame: ByteArray): Boolean {
        for (b in frame) if (b.toInt() != 0) return false
        return true
    }

    private suspend fun runLink(peer: Peer) {
        val id = peer.id
        var conn: Connection? = null
        try {
            conn = connector.connect(peer.host, peer.port, CONNECT_TIMEOUT_MS)
            conns[id] = conn
            if (id in userClosed) throw PeerLeftException()
            val (keys, sessionId, fmt) = handshake(conn, peer)
            conn.setReadTimeout(0)
            val link = RecipientLink(conn, FrameCipher(keys.key, sessionId), FrameCodecs.encoder(fmt)) { table.receiving(id) }
            links[id] = link
            table.connected(id)
            link.run()
            table.disconnected(id, null)
        } catch (e: CancellationException) {
            table.disconnected(id, null)
            throw e
        } catch (e: PeerLeftException) {
            table.disconnected(id, if (id in userClosed) null else "left")
        } catch (e: IOException) {
            if (id in userClosed) table.disconnected(id, null) else table.failed(id, failureCode(e))
        } finally {
            links.remove(id)
            conns.remove(id)
            conn?.close()
        }
    }

    private data class Handshake(val keys: app.nearaudio.transport.SessionKeys, val sessionId: ByteArray, val format: AudioFormatId)

    private fun handshake(conn: Connection, peer: Peer): Handshake {
        val fmt = format()
        val sessionId = ByteArray(Protocol.SESSION_ID_BYTES).also { random.nextBytes(it) }
        val pair = SessionCrypto.newKeyPair()
        val pub = pair.public.encoded
        conn.setReadTimeout(HANDSHAKE_TIMEOUT_MS)
        Codec.write(conn.output, Message.Hello(Protocol.VERSION, sessionId, alias().take(Protocol.MAX_ALIAS_CHARS), SessionCrypto.sha256(pub), fmt.id))
        val reply = when (val m = Codec.read(conn.input)) {
            is Message.HelloReply -> m
            is Message.Reject -> throw RejectedException(m.reason)
            else -> throw ProtocolException("unexpected message during handshake")
        }
        if (reply.version != Protocol.VERSION) throw RejectedException(RejectReason.UNSUPPORTED_VERSION)
        Codec.write(conn.output, Message.KeyReveal(pub))
        val keys = SessionCrypto.derive(pair.private, reply.publicKey, pub, reply.publicKey, sessionId)
        table.setVerification(peer.id, keys.verificationCode)
        conn.setReadTimeout(ACCEPT_TIMEOUT_MS)
        when (val m = Codec.read(conn.input)) {
            is Message.Accept -> if (!MessageDigest.isEqual(m.tag, keys.acceptTag)) throw ProtocolException("accept tag mismatch")
            is Message.Reject -> throw RejectedException(m.reason)
            else -> throw ProtocolException("unexpected message awaiting accept")
        }
        return Handshake(keys, sessionId, fmt)
    }

    private fun failureCode(e: IOException): String = when (e) {
        is RejectedException -> when (e.reason) {
            RejectReason.BUSY -> "busy"
            RejectReason.UNSUPPORTED_VERSION -> "version"
            else -> "declined"
        }
        is StalledException -> "stalled"
        is SocketTimeoutException -> "timeout"
        is ProtocolException -> "protocol"
        is EOFException -> "lost"
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.UnknownHostException -> "unreachable"
        else -> "lost"
    }
}
