package app.nearaudio.session

import androidx.annotation.StringRes

data class UiError(@StringRes val res: Int, val arg: String? = null)

data class IncomingUi(val alias: String, val code: String)

data class ReceivingUi(val alias: String, val code: String, val playing: Boolean)

data class UiState(
    val alias: String,
    val maxRecipients: Int,
    val discovering: Boolean = false,
    val peers: List<Recipient> = emptyList(),
    val sharing: Boolean = false,
    val captureSilent: Boolean = false,
    val incoming: IncomingUi? = null,
    val receiving: ReceivingUi? = null,
    val error: UiError? = null,
)
