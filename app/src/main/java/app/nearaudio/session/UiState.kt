package app.nearaudio.session

import androidx.annotation.StringRes
import app.nearaudio.audio.AudioFormatId

enum class ShareMode { WIFI, BLUETOOTH, WIRED }

/** USB = phone-to-phone USB cable (digital); LINE = listen to a wired audio input (3.5 mm jack / USB audio adapter). */
enum class WiredKind { USB, LINE }

/** Receiver jitter buffer in 20 ms frames: lower = less delay, higher = fewer dropouts. */
enum class LatencyMode(val frames: Int) { LOW(4), BALANCED(7), STABLE(12), SAFE(24) }

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
    val wiredKind: WiredKind = WiredKind.USB,
    val latency: LatencyMode = LatencyMode.BALANCED,
    val btQuality: AudioFormatId = AudioFormatId.AAC_STEREO_64,
    val cableLinked: Boolean = false,
    val lineActive: Boolean = false,
    val btHeadsetWarning: Boolean = false,
    val peers: List<Recipient> = emptyList(),
    val sharing: Boolean = false,
    val captureSilent: Boolean = false,
    val incoming: IncomingUi? = null,
    val receiving: ReceivingUi? = null,
    val error: UiError? = null,
)
