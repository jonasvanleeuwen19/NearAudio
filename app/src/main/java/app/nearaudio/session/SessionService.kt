package app.nearaudio.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.nearaudio.NearAudioApp
import app.nearaudio.R
import app.nearaudio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a running session alive with a persistent notification and Stop
 * action. Type mediaProjection while sharing, mediaPlayback while receiving. Stops itself when idle.
 */
class SessionService : Service() {
    companion object {
        private const val ACTION_SHARE = "app.nearaudio.SHARE"
        private const val ACTION_RECEIVE = "app.nearaudio.RECEIVE"
        private const val ACTION_STOP = "app.nearaudio.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 1

        fun startSharing(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, SessionService::class.java).setAction(ACTION_SHARE)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun startReceiving(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java).setAction(ACTION_RECEIVE))
        }
    }

    private val engine get() = (application as NearAudioApp).engine
    private var scope: CoroutineScope? = null
    private var sawActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            engine.state.collect { st ->
                val active = st.sharing || st.receiving != null
                if (active) {
                    sawActive = true
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(st))
                } else if (sawActive) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.endAll()
                stopSelf()
            }
            ACTION_RECEIVE -> {
                sawActive = false
                enter(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
            ACTION_SHARE -> {
                sawActive = false
                enter(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                if (!beginSharing(intent)) stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun enter(type: Int) {
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(engine.state.value), type)
    }

    private fun beginSharing(intent: Intent): Boolean {
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            engine.reportError(R.string.err_consent_denied)
            return false
        }
        var projection: MediaProjection? = null
        try {
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        } catch (e: SecurityException) {
            engine.reportError(R.string.err_capture_init, e.message)
            return false
        }
        if (projection == null) {
            engine.reportError(R.string.err_capture_init, "no projection")
            return false
        }
        // Required before capture on Android 14+; also how we learn the system ended the projection.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                engine.endAll()
            }
        }, Handler(Looper.getMainLooper()))
        return engine.startSharing(projection)
    }

    private fun buildNotification(st: UiState): Notification {
        val text = when {
            st.sharing -> getString(R.string.notif_sharing, st.peers.count { it.isLive })
            st.receiving != null -> getString(R.string.notif_receiving, st.receiving.alias)
            else -> getString(R.string.app_name)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }
}
