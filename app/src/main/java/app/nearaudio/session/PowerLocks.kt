package app.nearaudio.session

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Held only while a session runs: keeps the CPU awake with the screen off and asks Wi-Fi for its
 * low-latency mode (no power-save delays, a common cause of audio stutter).
 */
class PowerLocks(ctx: Context) {
    private val app = ctx.applicationContext
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire() {
        if (wake == null) {
            wake = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nearaudio:session")
                ?.also {
                    it.setReferenceCounted(false)
                    it.acquire(6 * 60 * 60 * 1000L)
                }
        }
        if (wifi == null) {
            wifi = app.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "nearaudio:wifi")
                ?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
        }
    }

    @Synchronized
    fun release() {
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        wake = null
        wifi = null
    }
}
