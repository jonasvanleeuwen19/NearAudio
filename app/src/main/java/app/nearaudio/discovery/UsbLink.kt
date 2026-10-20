package app.nearaudio.discovery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import app.nearaudio.transport.Connection
import app.nearaudio.transport.Connector
import app.nearaudio.transport.UsbAccessoryConnection
import app.nearaudio.transport.UsbHostConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Phone-to-phone USB cable using Android Open Accessory (AOA).
 * The SHARING phone must be the USB host (the one with the OTG adapter / that powers the link): it
 * switches the other phone into accessory mode and talks over bulk endpoints. The LISTENING phone
 * opens the accessory. Both then run the normal handshake + AES-GCM session over the cable.
 * Each Android permission dialog (USB device / accessory access) must be accepted by the user.
 */
class UsbLink(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onIncoming: (Connection) -> Unit,
    private val onHostReady: (Peer) -> Unit,
    private val onLinked: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val ACTION_PERMISSION = "app.nearaudio.USB_PERMISSION"
        const val PEER_ID = "75736230" // "usb0"
        private const val AOA_VID = 0x18D1
        private val AOA_PIDS = setOf(0x2D00, 0x2D01)
    }

    private val usb: UsbManager? = ctx.getSystemService(UsbManager::class.java)
    private var job: Job? = null
    private var prepared: Connection? = null
    private var accessoryOpen = false

    val connector = Connector { _, _, _ ->
        val c = synchronized(this) { prepared.also { prepared = null } }
        c ?: throw IOException("usb link not ready")
    }

    private fun permissionIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE,
    )

    private suspend fun awaitPermission(request: () -> Unit, has: () -> Boolean): Boolean {
        if (has()) return true
        val d = CompletableDeferred<Boolean>()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                d.complete(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            request()
            return withTimeoutOrNull(30_000) { d.await() } ?: false
        } finally {
            runCatching { ctx.unregisterReceiver(r) }
        }
    }

    // --- sharing phone (USB host) ---

    @Synchronized
    fun startHost() {
        val m = usb ?: run { onError("no-usb"); return }
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                var acc = m.deviceList.values.firstOrNull { it.vendorId == AOA_VID && it.productId in AOA_PIDS }
                if (acc == null) {
                    val candidates = m.deviceList.values.filter { it.deviceClass != UsbConstants.USB_CLASS_HUB }
                    if (candidates.isEmpty()) {
                        onError("no-device")
                        return@launch
                    }
                    var switched = false
                    for (d in candidates) {
                        if (!awaitPermission({ m.requestPermission(d, permissionIntent()) }, { m.hasPermission(d) })) continue
                        if (switchToAccessory(m, d)) {
                            switched = true
                            break
                        }
                    }
                    if (!switched) {
                        onError("aoa-unsupported")
                        return@launch
                    }
                    repeat(30) { // the other phone re-enumerates as an accessory
                        if (acc == null) {
                            delay(300)
                            acc = m.deviceList.values.firstOrNull { it.vendorId == AOA_VID && it.productId in AOA_PIDS }
                        }
                    }
                }
                val dev = acc ?: run { onError("no-accessory"); return@launch }
                if (!awaitPermission({ m.requestPermission(dev, permissionIntent()) }, { m.hasPermission(dev) })) {
                    onError("permission-denied")
                    return@launch
                }
                val c = m.openDevice(dev) ?: run { onError("open-failed"); return@launch }
                val iface = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { itf ->
                    (0 until itf.endpointCount).any { itf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                }
                val eps = iface?.let { itf -> (0 until itf.endpointCount).map { itf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } }
                val inEp = eps?.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                val outEp = eps?.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                if (iface == null || inEp == null || outEp == null || !c.claimInterface(iface, true)) {
                    c.close()
                    onError("no-endpoints")
                    return@launch
                }
                synchronized(this@UsbLink) { prepared?.close(); prepared = UsbHostConnection(c, iface, inEp, outEp) }
                onLinked(true)
                onHostReady(Peer(PEER_ID, "USB", "usb", 1))
            } catch (e: SecurityException) {
                onError("permission")
            }
        }
    }

    /** Standard AOA handshake (protocol >= 1): ask version, send identification strings, start accessory mode. */
    private fun switchToAccessory(m: UsbManager, d: UsbDevice): Boolean {
        val c = m.openDevice(d) ?: return false
        try {
            val ver = ByteArray(2)
            if (c.controlTransfer(0xC0, 51, 0, 0, ver, 2, 1000) < 0) return false
            if ((ver[0].toInt() and 0xFF) or ((ver[1].toInt() and 0xFF) shl 8) < 1) return false
            val strings = listOf("NearAudio", "Share", "NearAudio audio sharing", "1", "https://github.com/", "1")
            for ((i, s) in strings.withIndex()) {
                val b = (s + "\u0000").toByteArray()
                if (c.controlTransfer(0x40, 52, 0, i, b, b.size, 1000) < 0) return false
            }
            return c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) >= 0
        } finally {
            c.close()
        }
    }

    // --- listening phone (USB accessory) ---

    @Synchronized
    fun startAccessory() {
        val m = usb ?: run { onError("no-usb"); return }
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val acc = m.accessoryList?.firstOrNull()
                    onLinked(acc != null)
                    if (acc != null && !accessoryOpen) {
                        if (awaitPermission({ m.requestPermission(acc, permissionIntent()) }, { m.hasPermission(acc) })) {
                            val pfd = m.openAccessory(acc)
                            if (pfd != null) {
                                accessoryOpen = true
                                onIncoming(UsbAccessoryConnection(pfd) { accessoryOpen = false })
                            } else {
                                onError("open-failed")
                                delay(2000)
                            }
                        } else {
                            onError("permission-denied")
                            delay(5000)
                        }
                    }
                    delay(500)
                }
            } catch (e: SecurityException) {
                onError("permission")
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        prepared?.let { runCatching { it.close() } }
        prepared = null
        accessoryOpen = false
        onLinked(false)
    }
}
