package app.nearaudio.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Sharing phone side of a USB cable (USB host talking Android Open Accessory to the other phone).
 * bulkTransfer has a timeout but cannot tell "timeout" from "error", so reads run in 1 s slices:
 * a failure that returns immediately is an error, one that used the whole slice is a timeout.
 */
class UsbHostConnection(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : Connection {
    @Volatile private var timeoutMs = 0

    @Volatile private var closed = false

    override val input: InputStream = BufferedInputStream(object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val start = System.nanoTime()
            while (true) {
                if (closed) throw IOException("usb closed")
                val t = timeoutMs
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                if (t > 0 && elapsedMs >= t) throw SocketTimeoutException("usb read timed out")
                val slice = if (t > 0) minOf(1000L, t - elapsedMs).toInt().coerceAtLeast(1) else 1000
                val callStart = System.nanoTime()
                val n = conn.bulkTransfer(inEp, b, off, len, slice)
                if (n > 0) return n
                if (n < 0 && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callStart) < slice * 8L / 10) {
                    throw IOException("usb read failed (cable unplugged?)")
                }
            }
        }
    }, 16 * 1024)

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            // A transfer that is an exact multiple of the packet size has no short-packet terminator and
            // the receiving side would wait for more data; send the last byte separately.
            val mps = maxOf(outEp.maxPacketSize, 1)
            if (len > 1 && len % mps == 0) {
                raw(b, off, len - 1)
                raw(b, off + len - 1, 1)
            } else {
                raw(b, off, len)
            }
        }

        private fun raw(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (closed) throw IOException("usb closed")
                val n = conn.bulkTransfer(outEp, b, o, minOf(left, 16 * 1024), 5000)
                if (n <= 0) throw IOException("usb write failed")
                o += n
                left -= n
            }
        }
    }

    override fun setReadTimeout(ms: Int) {
        timeoutMs = ms
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }
}

/** Listening phone side: the file descriptor of the USB accessory. Reads time out via a closing timer. */
class UsbAccessoryConnection(private val pfd: ParcelFileDescriptor, private val onClosed: () -> Unit = {}) : Connection {
    @Volatile private var timeoutMs = 0

    @Volatile private var timedOut = false

    @Volatile private var closed = false
    private val fin = FileInputStream(pfd.fileDescriptor)
    private val fout = FileOutputStream(pfd.fileDescriptor)

    override val input: InputStream = BufferedInputStream(object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val t = timeoutMs
            if (t <= 0) return fin.read(b, off, len)
            val task = btTimer.schedule({
                timedOut = true
                close()
            }, t.toLong(), TimeUnit.MILLISECONDS)
            try {
                return fin.read(b, off, len)
            } catch (e: IOException) {
                if (timedOut) throw SocketTimeoutException("usb read timed out")
                throw e
            } finally {
                task.cancel(false)
            }
        }
    }, 16 * 1024) // accessory reads must use large buffers

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = fout.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, 16 * 1024)
                fout.write(b, o, n)
                o += n
                left -= n
            }
        }
    }

    override fun setReadTimeout(ms: Int) {
        timeoutMs = ms
    }

    override val waitForHello: Boolean get() = true

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { pfd.close() }
        onClosed()
    }
}
