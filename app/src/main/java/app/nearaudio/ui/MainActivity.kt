package app.nearaudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.nearaudio.NearAudioApp
import app.nearaudio.R
import app.nearaudio.session.SessionService

class MainActivity : ComponentActivity() {
    private val engine get() = (application as NearAudioApp).engine

    private val recordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askNotificationThenConsent() else engine.reportError(R.string.err_record_denied)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Sharing works without the notification being visible, so the result is not required.
        launchConsent()
    }
    private val offlinePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.startDiscovery() else engine.reportError(R.string.err_offline_permission)
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            SessionService.startSharing(this, r.resultCode, data)
        } else {
            engine.reportError(R.string.err_consent_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = Actions(
            onAlias = engine::setAlias,
            onToggleDiscovery = ::toggleDiscovery,
            onToggleSelect = engine::toggleSelect,
            onConnect = engine::connectSelected,
            onDisconnect = engine::disconnect,
            onStartSharing = ::beginSharing,
            onStopSharing = engine::endAll,
            onAnswer = engine::answerIncoming,
            onLeave = engine::leaveIncoming,
            onDismissError = engine::clearError,
            onMode = engine::setMode,
            onRole = engine::setRole,
            onJoin = engine::joinHost,
        )
        setContent {
            NearAudioTheme { MainScreen(engine.state, actions) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) engine.onUiBackgrounded()
    }

    /** Offline mode needs NEARBY_WIFI_DEVICES (API 33+) or, on older Android, location (Wi-Fi Direct requirement). */
    private fun toggleDiscovery() {
        val st = engine.state.value
        if (st.discovering) {
            engine.stopDiscovery()
            return
        }
        if (st.mode != app.nearaudio.session.ShareMode.OFFLINE) {
            engine.startDiscovery()
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (granted(perm)) engine.startDiscovery() else offlinePermission.launch(perm)
    }

    /** RECORD_AUDIO (required by AudioRecord for playback capture) -> notifications (API 33+) -> system capture consent. */
    private fun beginSharing() {
        if (!engine.canStartSharing()) {
            engine.reportError(R.string.err_need_connected)
            return
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) askNotificationThenConsent() else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askNotificationThenConsent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchConsent()
        }
    }

    private fun launchConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
