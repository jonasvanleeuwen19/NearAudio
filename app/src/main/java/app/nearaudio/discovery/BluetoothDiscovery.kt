package app.nearaudio.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.nearaudio.transport.BluetoothConnection
import app.nearaudio.transport.BluetoothConnector
import app.nearaudio.transport.Connection
import app.nearaudio.transport.Connector
import app.nearaudio.transport.sanitizeAlias
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline mode over Bluetooth LE. Listeners advertise our service UUID (connectable) with a random
 * per-launch id, an L2CAP PSM and a short alias in the scan response; sharers scan for that UUID,
 * then open an LE L2CAP connection-oriented channel to the PSM. No pairing, no Wi-Fi, no internet.
 * (Classic Bluetooth cannot be used: the classic address of the peer is not obtainable from a BLE
 * advertisement, and RFCOMM bandwidth is too low for PCM anyway.)
 */
@SuppressLint("MissingPermission") // permissions are requested by the activity before start*()
class BluetoothDiscovery(
    ctx: Context,
    private val scope: CoroutineScope,
    private val ownId: String,
    private val onFound: (Peer) -> Unit,
    private val onLost: (String) -> Unit,
    private val onIncoming: (Connection) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e1a5f3c-8b2d-4c7e-9a41-0d3f5b7c9e21")
        const val COMPANY_ID = 0xFFFF // reserved for testing/internal use; filtered by our UUID anyway
        const val STALE_MS = 15_000L
    }

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    val connector: Connector = BluetoothConnector(adapter)

    private var server: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var sweepJob: Job? = null
    private var advCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private val seen = ConcurrentHashMap<String, Pair<Peer, Long>>()

    private fun ready(): BluetoothAdapter? {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onError("bluetooth-off")
            return null
        }
        return a
    }

    // --- listener: accept L2CAP + advertise ---

    @Synchronized
    fun startListening(alias: String): Boolean {
        val a = ready() ?: return false
        try {
            val adv = a.bluetoothLeAdvertiser ?: run { onError("no-advertiser"); return false }
            val s = a.listenUsingInsecureL2capChannel()
            server = s
            acceptJob = scope.launch(Dispatchers.IO) {
                try {
                    while (true) onIncoming(BluetoothConnection(s.accept()))
                } catch (e: IOException) {
                    // closed by stop()
                }
            }
            val payload = ByteArray(6).also {
                it[0] = 1
                it[1] = s.psm.toByte()
                val idBytes = ownId.chunked(2).map { h -> h.toInt(16).toByte() }
                for (i in 0 until 4) it[2 + i] = idBytes[i]
            } + shortAlias(alias)
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) = onError("advertise/$errorCode")
            }
            advCallback = cb
            adv.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).addManufacturerData(COMPANY_ID, payload).build(),
                cb,
            )
            return true
        } catch (e: SecurityException) {
            onError("permission")
        } catch (e: IOException) {
            onError("l2cap: ${e.message}")
        }
        return false
    }

    private fun shortAlias(alias: String): ByteArray {
        var a = alias
        while (a.toByteArray(Charsets.UTF_8).size > 16) a = a.dropLast(1)
        return a.toByteArray(Charsets.UTF_8)
    }

    // --- sharer: scan ---

    @Synchronized
    fun startScanning(): Boolean {
        val a = ready() ?: return false
        try {
            val scanner = a.bluetoothLeScanner ?: run { onError("no-scanner"); return false }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
                override fun onScanFailed(errorCode: Int) = onError("scan/$errorCode")
            }
            scanCallback = cb
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            sweepJob = scope.launch {
                while (true) {
                    delay(5000)
                    val now = System.currentTimeMillis()
                    for ((id, v) in seen) if (now - v.second > STALE_MS && seen.remove(id) != null) onLost(id)
                }
            }
            return true
        } catch (e: SecurityException) {
            onError("permission")
        }
        return false
    }

    private fun handle(r: ScanResult) {
        val md = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
        if (md.size < 6 || md[0].toInt() != 1) return
        val psm = md[1].toInt() and 0xFF
        val id = (2 until 6).joinToString("") { "%02x".format(md[it]) }
        if (id == ownId || psm == 0) return
        val peer = Peer(id, sanitizeAlias(String(md, 6, md.size - 6, Charsets.UTF_8)), r.device.address, psm)
        val prev = seen.put(id, peer to System.currentTimeMillis())
        if (prev == null || prev.first != peer) onFound(peer)
    }

    @Synchronized
    fun stopAdvertising() {
        advCallback?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
        advCallback = null
    }

    /** Stops the radio scan (it competes with streaming for airtime) but keeps the found peers. */
    @Synchronized
    fun stopScan() {
        sweepJob?.cancel()
        sweepJob = null
        scanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCallback = null
    }

    @Synchronized
    fun stop() {
        stopScan()
        advCallback?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
        advCallback = null
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        acceptJob = null
        seen.clear()
    }
}
