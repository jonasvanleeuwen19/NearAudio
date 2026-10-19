package app.nearaudio.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.nearaudio.transport.sanitizeAlias
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds other NearAudio instances on the local network with Android NSD (mDNS/DNS-SD) and advertises
 * this one. Needs no runtime permission. The service name carries a random per-launch id; the
 * advertised alias is display text only and never used as identity.
 */
class PeerDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    private val ownId: String,
    private val onFound: (Peer) -> Unit,
    private val onLost: (String) -> Unit,
    private val onError: (Int) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_nearaudio._tcp."
        const val SERVICE_PREFIX = "na-"
        private val ID_REGEX = Regex("[0-9a-f]{8}")
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val namesToIds = ConcurrentHashMap<String, String>()

    @Volatile private var ownName: String? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolveQueue: Channel<NsdServiceInfo>? = null
    private var resolver: Job? = null

    @Synchronized
    fun start(alias: String, port: Int) {
        if (discovery != null) return
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        resolveQueue = queue
        // NSD allows only one resolve at a time, so resolves are serialised.
        resolver = scope.launch(Dispatchers.IO) { for (info in queue) resolveOne(info) }

        val info = NsdServiceInfo()
        info.serviceName = SERVICE_PREFIX + ownId
        info.serviceType = SERVICE_TYPE
        info.setPort(port)
        info.setAttribute("v", "1")
        info.setAttribute("id", ownId)
        info.setAttribute("a", alias.take(24))

        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                ownName = si.serviceName
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) = onError(errorCode)
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        registration = reg
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)

        val disc = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onError(errorCode)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                val name = si.serviceName ?: return
                if (si.serviceType?.contains("_nearaudio") != true) return
                if (!name.startsWith(SERVICE_PREFIX) || name == ownName) return
                queue.trySend(si)
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                namesToIds.remove(si.serviceName)?.let(onLost)
            }
        }
        discovery = disc
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery = null
        registration = null
        resolver?.cancel()
        resolveQueue?.close()
        resolveQueue = null
        namesToIds.clear()
        ownName = null
    }

    @Suppress("DEPRECATION") // resolveService is the only option that also works on API 29-33
    private suspend fun resolveOne(info: NsdServiceInfo) {
        val done = CompletableDeferred<NsdServiceInfo?>()
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    done.complete(null)
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    done.complete(si)
                }
            })
        } catch (e: IllegalArgumentException) {
            return
        }
        val resolved = withTimeoutOrNull(5000) { done.await() } ?: return
        val attrs = resolved.attributes
        fun attr(key: String): String? = attrs[key]?.toString(Charsets.UTF_8)
        val id = attr("id") ?: return
        val host = resolved.host?.hostAddress ?: return
        if (!ID_REGEX.matches(id) || id == ownId || attr("v") != "1" || resolved.port <= 0) return
        namesToIds[resolved.serviceName] = id
        onFound(Peer(id, sanitizeAlias(attr("a")), host, resolved.port))
    }
}
