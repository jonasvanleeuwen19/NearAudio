package app.nearaudio.session

import android.app.Application
import android.media.projection.MediaProjection
import app.nearaudio.R
import app.nearaudio.audio.AudioPlayer
import app.nearaudio.audio.PlaybackCaptureSource
import app.nearaudio.discovery.Peer
import app.nearaudio.discovery.PeerDiscovery
import app.nearaudio.discovery.WifiDirect
import app.nearaudio.transport.Codec
import app.nearaudio.transport.Connection
import app.nearaudio.transport.IncomingListener
import app.nearaudio.transport.Message
import app.nearaudio.transport.RejectReason
import app.nearaudio.transport.TcpConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Application-scoped glue between discovery, sender/receiver sessions and the UI. Holds no persistent data. */
class Engine(private val app: Application) {
    companion object {
        const val DECISION_TIMEOUT_S = 30L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownId = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val table = RecipientTable()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val _state = MutableStateFlow(UiState(alias = "Phone ${Random.nextInt(1000, 10000)}", maxRecipients = table.maxRecipients))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val sender = SenderSession(
        scope, table, TcpConnector, { _state.value.alias },
        onSilence = { silent -> _state.update { it.copy(captureSilent = silent) } },
        onCaptureFailed = { msg ->
            setError(R.string.err_capture_failed, msg)
            endAll()
        },
    )
    private val listener = IncomingListener(scope, ::onIncoming)
    private val discovery = PeerDiscovery(
        app, scope, ownId,
        onFound = { p ->
            peers[p.id] = p
            table.peerSeen(p.id, p.alias)
        },
        onLost = { id ->
            table.peerLost(id)
            if (table.get(id) == null) peers.remove(id)
        },
        onError = { code -> setError(R.string.err_discovery, code.toString()) },
    )

    private val wifiDirect = WifiDirect(
        app, scope,
        onAnnounce = { p ->
            peers[p.id] = p
            table.peerSeen(p.id, p.alias)
        },
        onHosts = { hs -> _state.update { it.copy(offlineHosts = hs) } },
        onJoined = { _state.update { it.copy(offlineJoined = true) } },
        onError = { code -> setError(R.string.err_offline, code) },
    )

    private val incomingActive = AtomicBoolean(false)

    @Volatile private var pendingDecision: CompletableFuture<Boolean>? = null

    @Volatile private var incomingConn: Connection? = null

    @Volatile private var userLeft = false

    init {
        scope.launch {
            table.changes.collect { list ->
                _state.update { it.copy(peers = list) }
                // Nobody left to listen: stop capturing instead of recording for no one.
                if (_state.value.sharing && list.none { it.isLive }) endAll()
            }
        }
    }

    fun setAlias(alias: String) {
        if (!_state.value.discovering) _state.update { it.copy(alias = alias.take(24)) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun reportError(@androidx.annotation.StringRes res: Int, arg: String? = null) = setError(res, arg)

    private fun setError(res: Int, arg: String? = null) = _state.update { it.copy(error = UiError(res, arg)) }

    // --- discovery ---

    private fun locked() = _state.value.discovering || _state.value.sharing || _state.value.receiving != null

    fun setMode(m: ShareMode) {
        if (!locked()) _state.update { it.copy(mode = m) }
    }

    fun setRole(r: Role) {
        if (!locked()) _state.update { it.copy(role = r) }
    }

    fun startDiscovery() {
        val s = _state.value
        if (s.discovering) return
        try {
            when {
                s.mode == ShareMode.WIFI -> discovery.start(s.alias, listener.start())
                s.role == Role.SHARE -> wifiDirect.startHost(s.alias)
                else -> wifiDirect.startClient(s.alias, ownId, listener.start())
            }
            _state.update { it.copy(discovering = true, error = null, offlineHosts = emptyList(), offlineJoined = false) }
        } catch (e: IOException) {
            discovery.stop(); wifiDirect.stop(); listener.stop()
            setError(R.string.err_listen, e.message)
        } catch (e: RuntimeException) {
            discovery.stop(); wifiDirect.stop(); listener.stop()
            setError(R.string.err_discovery, e.message ?: "?")
        }
    }

    fun joinHost(address: String) = wifiDirect.join(address)

    fun stopDiscovery() {
        if (!_state.value.discovering) return
        discovery.stop()
        wifiDirect.stop()
        listener.stop()
        table.dropUnconnected()
        peers.keys.retainAll(table.snapshot().map { it.peerId }.toSet())
        _state.update { it.copy(discovering = false, offlineHosts = emptyList(), offlineJoined = false) }
    }

    /** Called when the UI leaves the foreground: listening for strangers stops unless a session is running. */
    fun onUiBackgrounded() {
        val s = _state.value
        if (!s.sharing && s.receiving == null) {
            pendingDecision?.complete(false)
            stopDiscovery()
        }
    }

    // --- sender role ---

    fun toggleSelect(id: String) {
        val cur = table.get(id) ?: return
        val wantSelected = cur.state != RecipientState.SELECTED
        if (!table.setSelected(id, wantSelected) && wantSelected) setError(R.string.err_max_recipients, table.maxRecipients.toString())
    }

    fun connectSelected() {
        if (_state.value.receiving != null || incomingActive.get()) {
            setError(R.string.err_already_receiving)
            return
        }
        for (id in table.selectedIds()) peers[id]?.let { sender.connect(it) } ?: table.failed(id, "unreachable")
    }

    fun disconnect(id: String) = sender.disconnect(id)

    fun canStartSharing(): Boolean = table.connectedIds().isNotEmpty() && !sender.isSharing && _state.value.receiving == null

    /** Takes ownership of [projection]: it is stopped on every failure path and when sharing ends. */
    fun startSharing(projection: MediaProjection): Boolean {
        if (!canStartSharing()) {
            projection.stop()
            setError(R.string.err_need_connected)
            return false
        }
        val src = try {
            PlaybackCaptureSource(projection)
        } catch (e: Exception) {
            projection.stop()
            setError(R.string.err_capture_init, e.message ?: e.javaClass.simpleName)
            return false
        }
        sender.startSharing(src)
        _state.update { it.copy(sharing = true, error = null, captureSilent = false) }
        return true
    }

    /** Stops capture, tells all recipients to stop, closes everything. Safe to call repeatedly. */
    fun endAll() {
        sender.stopAll()
        leaveIncoming()
        _state.update { it.copy(sharing = false, captureSilent = false) }
    }

    // --- receiver role ---

    fun answerIncoming(accept: Boolean) {
        pendingDecision?.complete(accept)
    }

    fun leaveIncoming() {
        userLeft = true
        pendingDecision?.complete(false)
        incomingConn?.let { runCatching { it.close() } }
    }

    private fun onIncoming(conn: Connection) {
        val busy = _state.value.sharing || table.snapshot().any { it.isLive }
        if (busy || !incomingActive.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                try {
                    Codec.write(conn.output, Message.Reject(RejectReason.BUSY))
                } catch (e: IOException) {
                    // peer already gone
                } finally {
                    conn.close()
                }
            }
            return
        }
        scope.launch(Dispatchers.IO) { runIncoming(conn) }
    }

    private fun runIncoming(conn: Connection) {
        val session = ReceiverSession({ _state.value.alias }, AudioPlayer(app))
        incomingConn = conn
        userLeft = false
        var peerAlias = ""
        var code = ""
        try {
            session.handle(
                conn,
                decide = { req ->
                    peerAlias = req.alias
                    code = req.code
                    awaitDecision(req)
                },
                onPlaying = { playing ->
                    _state.update { it.copy(receiving = ReceivingUi(peerAlias, code, playing)) }
                    if (!playing) SessionService.startReceiving(app)
                },
            )
        } catch (e: IOException) {
            // Failures before the user saw a prompt are just port noise; don't alarm the user.
            if (!userLeft && peerAlias.isNotEmpty()) setError(R.string.err_incoming_ended)
        } finally {
            runCatching { conn.close() }
            incomingConn = null
            _state.update { it.copy(receiving = null, incoming = null) }
            incomingActive.set(false)
        }
    }

    private fun awaitDecision(req: IncomingRequest): Boolean {
        val f = CompletableFuture<Boolean>()
        pendingDecision = f
        _state.update { it.copy(incoming = IncomingUi(req.alias, req.code)) }
        try {
            return f.get(DECISION_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            return false
        } finally {
            pendingDecision = null
            _state.update { it.copy(incoming = null) }
        }
    }
}
