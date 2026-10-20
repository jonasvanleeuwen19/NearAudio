package app.nearaudio.session

import app.nearaudio.audio.FrameEncoder
import app.nearaudio.transport.Codec
import app.nearaudio.transport.Connection
import app.nearaudio.transport.FrameCipher
import app.nearaudio.transport.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class PeerLeftException : IOException("recipient left")
class StalledException : IOException("recipient too slow")

/**
 * One connected recipient. [offer] never blocks: the queue is bounded and drops the OLDEST frame
 * when full, so a slow recipient loses audio instead of delaying the capture loop or other
 * recipients. A watchdog closes the connection if a single write stalls.
 */
class RecipientLink(
    private val conn: Connection,
    private val cipher: FrameCipher,
    private val encoder: FrameEncoder = FrameEncoder { it },
    queueFrames: Int = DEFAULT_QUEUE_FRAMES,
    private val onFirstFrameSent: () -> Unit = {},
) {
    companion object {
        const val DEFAULT_QUEUE_FRAMES = 15 // 300 ms
        const val KEEPALIVE_MS = 2000L
        const val STALL_MS = 3000L
    }

    private val queue = Channel<ByteArray>(queueFrames, BufferOverflow.DROP_OLDEST)

    @Volatile private var writeStartedMs = 0L

    @Volatile private var stalled = false
    private var counter = 0L
    private var firstSent = false

    /** Frames must not be mutated after being offered (they are shared between links). */
    fun offer(frame: ByteArray) {
        queue.trySend(frame)
    }

    /** Graceful end: queued frames are flushed, then Stop is sent. */
    fun requestStop() {
        queue.close()
    }

    /** Returns normally after a graceful stop; throws [IOException] otherwise. Always closes [conn]. */
    suspend fun run(): Unit = coroutineScope {
        val reader = launch(Dispatchers.IO) { readUntilGone() }
        val watchdog = launch(Dispatchers.Default) {
            while (true) {
                delay(500)
                val t = writeStartedMs
                if (t != 0L && System.currentTimeMillis() - t > STALL_MS) {
                    stalled = true
                    conn.close()
                }
            }
        }
        try {
            writeLoop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (stalled) throw StalledException()
            throw e
        } finally {
            reader.cancel()
            watchdog.cancel()
            conn.close() // also unblocks the reader's blocking read
        }
    }

    private fun readUntilGone() {
        try {
            while (true) {
                if (Codec.read(conn.input) is Message.Stop) break
            }
        } catch (e: IOException) {
            // connection closed or garbage received: either way the recipient is gone
        }
        queue.close(PeerLeftException())
    }

    private suspend fun writeLoop() {
        while (true) {
            val r = withTimeoutOrNull(KEEPALIVE_MS) { queue.receiveCatching() }
            if (r == null) {
                timedWrite { Codec.write(conn.output, Message.Ping) }
                continue
            }
            val frame = r.getOrNull()
            if (frame == null) {
                r.exceptionOrNull()?.let { throw it }
                timedWrite { Codec.write(conn.output, Message.Stop) }
                return
            }
            timedWrite { Codec.write(conn.output, Message.Audio(counter, cipher.seal(counter, encoder.encode(frame)))) }
            counter++
            if (!firstSent) {
                firstSent = true
                onFirstFrameSent()
            }
        }
    }

    private inline fun timedWrite(block: () -> Unit) {
        writeStartedMs = System.currentTimeMillis()
        try {
            block()
        } finally {
            writeStartedMs = 0L
        }
    }
}
