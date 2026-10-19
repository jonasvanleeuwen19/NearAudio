package app.nearaudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nearaudio.R
import app.nearaudio.session.Recipient
import app.nearaudio.session.RecipientState
import app.nearaudio.session.UiState
import kotlinx.coroutines.flow.StateFlow

class Actions(
    val onAlias: (String) -> Unit,
    val onToggleDiscovery: () -> Unit,
    val onToggleSelect: (String) -> Unit,
    val onConnect: () -> Unit,
    val onDisconnect: (String) -> Unit,
    val onStartSharing: () -> Unit,
    val onStopSharing: () -> Unit,
    val onAnswer: (Boolean) -> Unit,
    val onLeave: () -> Unit,
    val onDismissError: () -> Unit,
)

@Composable
fun MainScreen(stateFlow: StateFlow<UiState>, actions: Actions) {
    val s by stateFlow.collectAsStateWithLifecycle()
    val rcv = s.receiving
    val live = s.peers.count { it.isLive }
    val connected = s.peers.count { it.state == RecipientState.CONNECTED || it.state == RecipientState.RECEIVING }
    val selected = s.peers.count { it.state == RecipientState.SELECTED }

    s.incoming?.let { inc ->
        AlertDialog(
            onDismissRequest = { actions.onAnswer(false) },
            title = { Text(stringResource(R.string.incoming_title)) },
            text = { Text(stringResource(R.string.incoming_text, inc.alias, inc.code)) },
            confirmButton = { TextButton(onClick = { actions.onAnswer(true) }) { Text(stringResource(R.string.accept)) } },
            dismissButton = { TextButton(onClick = { actions.onAnswer(false) }) { Text(stringResource(R.string.decline)) } },
        )
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium) }

            item {
                val status = when {
                    s.sharing -> stringResource(R.string.status_sharing, live)
                    rcv != null && rcv.playing -> stringResource(R.string.status_receiving, rcv.alias)
                    rcv != null -> stringResource(R.string.status_receiving_wait, rcv.alias)
                    s.discovering -> stringResource(R.string.status_discovering)
                    else -> stringResource(R.string.status_idle)
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.transport_info), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            s.error?.let { err ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(err.res, err.arg ?: ""),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            )
                            TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.dismiss)) }
                        }
                    }
                }
            }

            if (s.sharing && s.captureSilent) {
                item { Text(stringResource(R.string.silent_warning), color = MaterialTheme.colorScheme.tertiary) }
            }

            if (rcv != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.code_label, rcv.code))
                        OutlinedButton(onClick = actions.onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.leave))
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = s.alias,
                    onValueChange = actions.onAlias,
                    enabled = !s.discovering,
                    singleLine = true,
                    label = { Text(stringResource(R.string.alias_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Button(
                    onClick = actions.onToggleDiscovery,
                    enabled = !s.sharing && s.receiving == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (s.discovering) R.string.discovery_stop else R.string.discovery_start))
                }
            }

            item { Text(stringResource(R.string.peers_title), style = MaterialTheme.typography.titleMedium) }

            if (s.peers.isEmpty()) {
                item {
                    Text(
                        stringResource(if (s.discovering) R.string.peers_empty else R.string.peers_hint_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(s.peers, key = { it.peerId }) { r ->
                    PeerRow(r, s.sharing, { actions.onToggleSelect(r.peerId) }, { actions.onDisconnect(r.peerId) })
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.onConnect,
                        enabled = selected > 0 && s.receiving == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.connect_selected, selected)) }

                    if (s.sharing) {
                        Button(onClick = actions.onStopSharing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.stop_sharing))
                        }
                    } else {
                        Button(
                            onClick = actions.onStartSharing,
                            enabled = connected > 0 && s.receiving == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.start_sharing)) }
                    }
                    Text(stringResource(R.string.max_recipients_note, s.maxRecipients), style = MaterialTheme.typography.bodySmall)
                }
            }

            item { Text(stringResource(R.string.limits_info), style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.permission_info), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PeerRow(r: Recipient, sharing: Boolean, onToggle: () -> Unit, onDisconnect: () -> Unit) {
    val selectable = r.state == RecipientState.DISCOVERED || r.state == RecipientState.SELECTED ||
        r.state == RecipientState.DISCONNECTED || r.state == RecipientState.FAILED
    val name = "${r.alias} · #${r.peerId.take(4)}" // id suffix tells apart identical names
    val stateText = stringResource(stateRes(r.state)) + (detailRes(r.detail)?.let { " – " + stringResource(it) } ?: "")

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = r.state == RecipientState.SELECTED,
                enabled = selectable && !sharing,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics(mergeDescendants = true) { contentDescription = "$name, $stateText" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = r.state == RecipientState.SELECTED, onCheckedChange = null, enabled = selectable && !sharing)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(stateText, style = MaterialTheme.typography.bodySmall)
            if (r.isLive && r.verificationCode != null) {
                Text(stringResource(R.string.code_label, r.verificationCode), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (r.isLive) {
            TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.disconnect)) }
        }
    }
}

private fun stateRes(s: RecipientState): Int = when (s) {
    RecipientState.DISCOVERED -> R.string.state_discovered
    RecipientState.SELECTED -> R.string.state_selected
    RecipientState.CONNECTING -> R.string.state_connecting
    RecipientState.CONNECTED -> R.string.state_connected
    RecipientState.RECEIVING -> R.string.state_receiving
    RecipientState.DISCONNECTED -> R.string.state_disconnected
    RecipientState.FAILED -> R.string.state_failed
}

private fun detailRes(code: String?): Int? = when (code) {
    "declined" -> R.string.detail_declined
    "busy" -> R.string.detail_busy
    "timeout" -> R.string.detail_timeout
    "left" -> R.string.detail_left
    "lost" -> R.string.detail_lost
    "protocol" -> R.string.detail_protocol
    "version" -> R.string.detail_version
    "unreachable" -> R.string.detail_unreachable
    "stalled" -> R.string.detail_stalled
    else -> null
}
