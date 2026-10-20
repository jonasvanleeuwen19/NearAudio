package app.nearaudio.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import androidx.core.content.ContextCompat
import app.nearaudio.transport.Announce
import app.nearaudio.transport.sanitizeAlias
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class OfflineHost(val address: String, val name: String)

/**
 * Offline mode without any router or internet: the sharing phone creates a Wi-Fi Direct group and
 * advertises itself; listeners find it via Wi-Fi Direct service discovery, join the group, and
 * announce their TCP port to the owner. Audio then uses the same TCP/AES-GCM session as Wi-Fi mode.
 * Needs NEARBY_WIFI_DEVICES (API 33+) or location (older) at runtime, checked by the caller.
 */
class WifiDirect(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onAnnounce: (Peer) -> Unit,
    private val onHosts: (List<OfflineHost>) -> Unit,
    private val onJoined: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val manager: WifiP2pManager? = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var server: ServerSocket? = null
    private var serverJob: Job? = null
    private var announceJob: Job? = null
    private var refreshJob: Job? = null
    private var receiver: BroadcastReceiver? = null
    private var announceInfo: Triple<String, Int, String>? = null
    private val hosts = LinkedHashMap<String, OfflineHost>()

    private val noop = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason: Int) {}
    }

    private fun listener(what: String, ok: () -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = ok()
        override fun onFailure(reason: Int) = onError("$what/$reason")
    }

    private fun open(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = manager
        if (m == null) {
            onError("unsupported")
            return null
        }
        val ch = channel ?: m.initialize(ctx, Looper.getMainLooper(), null)
        if (ch == null) {
            onError("channel")
            return null
        }
        channel = ch
        return m to ch
    }

    // --- sharing phone ---

    @Synchronized
    fun startHost(alias: String) {
        val (m, ch) = open() ?: return
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(Announce.PORT))
            server = s
            serverJob = scope.launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val sock = s.accept()
                        launch { handleAnnounce(sock) }
                    }
                } catch (e: IOException) {
                    // closed by stop()
                }
            }
            m.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    // A stale group from an earlier run is the usual cause: remove it and retry once.
                    m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = m.createGroup(ch, listener("group"))
                        override fun onFailure(reason: Int) = onError("group/$reason")
                    })
                }
            })
            val info = WifiP2pDnsSdServiceInfo.newInstance("nearaudio", "_nearaudio._tcp", mapOf("a" to alias.take(24)))
            m.addLocalService(ch, info, listener("service"))
        } catch (e: IOException) {
            onError("port: ${e.message}")
        } catch (e: SecurityException) {
            onError("permission")
        }
    }

    private fun handleAnnounce(sock: Socket) {
        sock.use {
            it.soTimeout = 3000
            val a = try {
                Announce.read(it.getInputStream())
            } catch (e: IOException) {
                return
            }
            val host = it.inetAddress?.hostAddress ?: return
            onAnnounce(Peer(a.id, sanitizeAlias(a.alias), host, a.port))
        }
    }

    // --- listening phone ---

    @Synchronized
    fun startClient(alias: String, id: String, port: Int) {
        val (m, ch) = open() ?: return
        announceInfo = Triple(id, port, alias.take(24))
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = checkGroup()
            }
            ContextCompat.registerReceiver(
                ctx, r, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = r
            m.setDnsSdResponseListeners(
                ch,
                WifiP2pManager.DnsSdServiceResponseListener { _, type, device ->
                    if (type.contains("_nearaudio")) {
                        synchronized(hosts) {
                            hosts[device.deviceAddress] = OfflineHost(device.deviceAddress, device.deviceName.orEmpty().ifBlank { "?" })
                            onHosts(hosts.values.toList())
                        }
                    }
                },
                WifiP2pManager.DnsSdTxtRecordListener { _, _, _ -> },
            )
            m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), listener("request") {
                m.discoverServices(ch, listener("discover"))
            })
            // Service discovery times out on its own; ask again periodically while searching.
            refreshJob = scope.launch {
                while (true) {
                    delay(20_000)
                    try {
                        m.discoverServices(ch, noop)
                    } catch (e: SecurityException) {
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            onError("permission")
        }
    }

    fun join(address: String) {
        val (m, ch) = open() ?: return
        try {
            val cfg = WifiP2pConfig()
            cfg.deviceAddress = address
            cfg.wps.setup = WpsInfo.PBC
            cfg.groupOwnerIntent = 0 // prefer to be the client; the sharing phone owns the group
            m.connect(ch, cfg, listener("connect"))
        } catch (e: SecurityException) {
            onError("permission")
        }
    }

    private fun checkGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info ->
                val host = info?.groupOwnerAddress?.hostAddress
                if (info != null && info.groupFormed && !info.isGroupOwner && host != null) announceTo(host)
            }
        } catch (e: SecurityException) {
            onError("permission")
        }
    }

    private fun announceTo(host: String) {
        if (announceJob?.isActive == true) return
        val (id, port, alias) = announceInfo ?: return
        announceJob = scope.launch(Dispatchers.IO) {
            repeat(8) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, Announce.PORT), 3000)
                        s.getOutputStream().write(Announce.encode(id, port, alias))
                    }
                    onJoined()
                    return@launch
                } catch (e: IOException) {
                    delay(1000) // owner may not be listening yet
                }
            }
            onError("announce")
        }
    }

    @Synchronized
    fun stop() {
        refreshJob?.cancel()
        announceJob?.cancel()
        serverJob?.cancel()
        runCatching { server?.close() }
        server = null
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            runCatching { m.clearServiceRequests(ch, noop) }
            runCatching { m.clearLocalServices(ch, noop) }
            runCatching { m.cancelConnect(ch, noop) }
            runCatching { m.stopPeerDiscovery(ch, noop) }
            runCatching { m.removeGroup(ch, noop) }
            runCatching { ch.close() }
        }
        channel = null
        synchronized(hosts) { hosts.clear() }
    }
}
