package app.nearaudio.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal val btTimer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "bt-timeout").also { it.isDaemon = true } }
    .apply { removeOnCancelPolicy = true }

/**
 * BluetoothSocket has no read timeout, so a timer closes the socket if a read takes too long
 * (all timeouts in the protocol are fatal anyway). Writes are chunked to the L2CAP MTU.
 */
class BluetoothConnection(private val socket: BluetoothSocket) : Connection {
    @Volatile private var timeoutMs = 0

    @Volatile private var timedOut = false
    private val rawIn = socket.inputStream
    private val rawOut = socket.outputStream
    private val maxChunk = maxOf(64, socket.maxTransmitPacketSize)

    override val input: InputStream = BufferedInputStream(object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val t = timeoutMs
            if (t <= 0) return rawIn.read(b, off, len)
            val task = btTimer.schedule({
                timedOut = true
                runCatching { socket.close() }
            }, t.toLong(), TimeUnit.MILLISECONDS)
            try {
                return rawIn.read(b, off, len)
            } catch (e: IOException) {
                if (timedOut) throw SocketTimeoutException("read timed out")
                throw e
            } finally {
                task.cancel(false)
            }
        }
    }, 4096)

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = rawOut.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, maxChunk)
                rawOut.write(b, o, n)
                o += n
                left -= n
            }
        }
        override fun flush() = rawOut.flush()
    }

    override fun setReadTimeout(ms: Int) {
        timeoutMs = ms
    }

    override val slowLink: Boolean get() = true

    override fun close() = socket.close()
}

/** Opens an insecure LE L2CAP channel (no pairing); security comes from our own handshake + AES-GCM. */
@SuppressLint("MissingPermission")
class BluetoothConnector(private val adapter: BluetoothAdapter?) : Connector {
    private val lock = Any()

    override fun connect(host: String, port: Int, timeoutMs: Int): Connection {
        val a = adapter ?: throw IOException("no bluetooth")
        try {
            val socket = a.getRemoteDevice(host).createInsecureL2capChannel(port)
            // One connect at a time: the Bluetooth stack handles parallel connects badly.
            synchronized(lock) {
                var fired = false
                val task = btTimer.schedule({
                    fired = true
                    runCatching { socket.close() }
                }, 10, TimeUnit.SECONDS)
                try {
                    socket.connect()
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    if (fired) throw SocketTimeoutException("bluetooth connect timed out")
                    throw e
                } finally {
                    task.cancel(false)
                }
            }
            return BluetoothConnection(socket)
        } catch (e: SecurityException) {
            throw IOException("bluetooth permission missing")
        } catch (e: IllegalArgumentException) {
            throw IOException("bad bluetooth address")
        }
    }
}
