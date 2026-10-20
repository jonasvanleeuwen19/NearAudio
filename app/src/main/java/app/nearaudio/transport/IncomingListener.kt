package app.nearaudio.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Accepts TCP connections while discovery is on. Connections get no trust until the user accepts. */
class IncomingListener(
    private val scope: CoroutineScope,
    private val onConnection: (Connection) -> Unit,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** Binds an ephemeral port and returns it. Throws [IOException] if binding fails. */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket()
        s.receiveBufferSize = 64 * 1024 // must be set before bind; keeps buffered (delayed) audio small
        s.bind(InetSocketAddress(0))
        server = s
        job = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val sock = s.accept()
                    sock.tcpNoDelay = true
                    onConnection(SocketConnection(sock))
                }
            } catch (e: IOException) {
                // server socket closed by stop()
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
    }
}
