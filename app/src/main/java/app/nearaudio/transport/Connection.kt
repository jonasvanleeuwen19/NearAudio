package app.nearaudio.transport

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

interface Connection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** 0 = no timeout. */
    fun setReadTimeout(ms: Int)
}

class SocketConnection(private val socket: Socket) : Connection {
    override val input: InputStream = BufferedInputStream(socket.getInputStream(), 8192)
    override val output: OutputStream = socket.getOutputStream()
    override fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() = socket.close()
}

fun interface Connector {
    /** Blocking; call off the main thread. */
    fun connect(host: String, port: Int, timeoutMs: Int): Connection
}

object TcpConnector : Connector {
    override fun connect(host: String, port: Int, timeoutMs: Int): Connection {
        val s = Socket()
        try {
            s.sendBufferSize = 32 * 1024 // a big kernel send buffer would hide seconds of audio delay
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            return SocketConnection(s)
        } catch (e: IOException) {
            s.close()
            throw e
        }
    }
}
