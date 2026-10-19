package app.nearaudio.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecipientState { DISCOVERED, SELECTED, CONNECTING, CONNECTED, RECEIVING, DISCONNECTED, FAILED }

/** [detail] is a short machine code (e.g. "declined", "timeout"); the UI maps it to a localized string. */
data class Recipient(
    val peerId: String,
    val alias: String,
    val state: RecipientState,
    val detail: String? = null,
    val verificationCode: String? = null,
) {
    val isLive: Boolean
        get() = state == RecipientState.CONNECTING || state == RecipientState.CONNECTED || state == RecipientState.RECEIVING
}

/** Thread-safe recipient state machine. Illegal transitions return false and change nothing. */
class RecipientTable(val maxRecipients: Int = DEFAULT_MAX_RECIPIENTS) {
    companion object {
        /** Each recipient costs ~1.5 Mbit/s of upload plus one encryption/socket thread; 4 is conservative for phone Wi-Fi. */
        const val DEFAULT_MAX_RECIPIENTS = 4
        private val IDLE = setOf(RecipientState.DISCOVERED, RecipientState.DISCONNECTED, RecipientState.FAILED)
    }

    private val items = LinkedHashMap<String, Recipient>()
    private val _changes = MutableStateFlow<List<Recipient>>(emptyList())
    val changes: StateFlow<List<Recipient>> = _changes.asStateFlow()

    private fun publish() {
        _changes.value = items.values.toList()
    }

    private fun slotsUsed() = items.values.count { it.state == RecipientState.SELECTED || it.isLive }

    @Synchronized fun snapshot(): List<Recipient> = items.values.toList()

    @Synchronized fun get(id: String): Recipient? = items[id]

    @Synchronized fun connectedIds(): List<String> =
        items.values.filter { it.state == RecipientState.CONNECTED || it.state == RecipientState.RECEIVING }.map { it.peerId }

    @Synchronized fun selectedIds(): List<String> =
        items.values.filter { it.state == RecipientState.SELECTED }.map { it.peerId }

    @Synchronized fun peerSeen(id: String, alias: String) {
        val cur = items[id]
        items[id] = cur?.copy(alias = alias) ?: Recipient(id, alias, RecipientState.DISCOVERED)
        publish()
    }

    /** A vanished peer is forgotten unless a connection to it is live (connections outlive discovery). */
    @Synchronized fun peerLost(id: String) {
        val cur = items[id] ?: return
        if (!cur.isLive) {
            items.remove(id)
            publish()
        }
    }

    @Synchronized fun setSelected(id: String, selected: Boolean): Boolean {
        val cur = items[id] ?: return false
        if (selected) {
            if (cur.state == RecipientState.SELECTED) return true
            if (cur.state !in IDLE || slotsUsed() >= maxRecipients) return false
            items[id] = cur.copy(state = RecipientState.SELECTED, detail = null, verificationCode = null)
        } else {
            if (cur.state != RecipientState.SELECTED) return false
            items[id] = cur.copy(state = RecipientState.DISCOVERED)
        }
        publish()
        return true
    }

    @Synchronized fun beginConnect(id: String): Boolean {
        val cur = items[id] ?: return false
        val ok = cur.state == RecipientState.SELECTED || (cur.state in IDLE && slotsUsed() < maxRecipients)
        if (!ok) return false
        items[id] = cur.copy(state = RecipientState.CONNECTING, detail = null, verificationCode = null)
        publish()
        return true
    }

    @Synchronized fun setVerification(id: String, code: String): Boolean {
        val cur = items[id] ?: return false
        if (cur.state != RecipientState.CONNECTING) return false
        items[id] = cur.copy(verificationCode = code)
        publish()
        return true
    }

    @Synchronized fun connected(id: String): Boolean = move(id, setOf(RecipientState.CONNECTING), RecipientState.CONNECTED, null)

    @Synchronized fun receiving(id: String): Boolean =
        move(id, setOf(RecipientState.CONNECTED, RecipientState.RECEIVING), RecipientState.RECEIVING, null)

    @Synchronized fun disconnected(id: String, detail: String? = null): Boolean =
        move(id, LIVE, RecipientState.DISCONNECTED, detail, clearCode = true)

    @Synchronized fun failed(id: String, detail: String): Boolean =
        move(id, LIVE, RecipientState.FAILED, detail, clearCode = true)

    /** Marks every live recipient disconnected (session ended). */
    @Synchronized fun endAll() {
        for (r in items.values.toList()) if (r.isLive) items[r.peerId] = r.copy(state = RecipientState.DISCONNECTED, detail = null, verificationCode = null)
        publish()
    }

    /** Called when discovery stops: forget everything that is not part of a live connection. */
    @Synchronized fun dropUnconnected() {
        items.values.removeAll { !it.isLive }
        publish()
    }

    private val LIVE = setOf(RecipientState.CONNECTING, RecipientState.CONNECTED, RecipientState.RECEIVING)

    private fun move(id: String, from: Set<RecipientState>, to: RecipientState, detail: String?, clearCode: Boolean = false): Boolean {
        val cur = items[id] ?: return false
        if (cur.state !in from) return false
        items[id] = cur.copy(state = to, detail = detail, verificationCode = if (clearCode) null else cur.verificationCode)
        publish()
        return true
    }
}
