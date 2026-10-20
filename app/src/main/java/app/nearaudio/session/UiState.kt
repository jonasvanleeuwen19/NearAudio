package app.nearaudio.session

import androidx.annotation.StringRes
import app.nearaudio.discovery.OfflineHost

enum class ShareMode { WIFI, OFFLINE }

enum class Role { SHARE, LISTEN }

data class UiError(@StringRes val res: Int, val arg: String? = null)

data class IncomingUi(val alias: String, val code: String)

data class ReceivingUi(val alias: String, val code: String, val playing: Boolean)

data class UiState(
    val alias: String,
    val maxRecipients: Int,
    val mode: ShareMode = ShareMode.WIFI,
    val role: Role = Role.SHARE,
    val discovering: Boolean = false,
    val offlineHosts: List<OfflineHost> = emptyList(),
    val offlineJoined: Boolean = false,
    val peers: List<Recipient> = emptyList(),
    val sharing: Boolean = false,
    val captureSilent: Boolean = false,
    val incoming: IncomingUi? = null,
    val receiving: ReceivingUi? = null,
    val error: UiError? = null,
)
