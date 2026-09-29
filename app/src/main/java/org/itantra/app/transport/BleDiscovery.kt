package org.itantra.app.transport

import android.annotation.SuppressLint
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BlePresence(val id: String, val name: String, val rssi: Int)
data class BleStatus(
    val scanning: Boolean = false, val advertising: Boolean = false,
    val peers: List<BlePresence> = emptyList(), val detail: String = "BLE discovers iTantra presence; Classic carries messages."
)

/** Bounded, service-filtered BLE discovery. BLE MACs are not Classic connection addresses. */
@SuppressLint("MissingPermission")
class BleDiscovery(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(BleStatus())
    val state = mutable.asStateFlow()
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var expiry: Job? = null
    private var scanExpiry: Job? = null
    private var generation = 0
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    val supported get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    fun start() {
        stop()
        check(supported) { "BLE is unavailable on this device" }
        val adapter = BluetoothAccess.requireAdapter(context)
        val epoch = ++generation
        val uuid = ParcelUuid(BluetoothAccess.BLE_UUID)
        mutable.value = BleStatus(detail = "Scanning for 30 seconds; advertising for up to 2 minutes.")
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (epoch != generation) return
                val nameBytes = result.scanRecord?.getServiceData(uuid)
                val name = nameBytes?.toString(Charsets.UTF_8)?.take(40)?.ifBlank { null } ?: "Nearby iTantra"
                val peer = BlePresence(result.device.address, name, result.rssi)
                mutable.update { it.copy(peers = (it.peers.filterNot { old -> old.id == peer.id } + peer).takeLast(64)) }
            }
            override fun onScanFailed(errorCode: Int) {
                if (epoch == generation) mutable.update { it.copy(scanning = false, detail = "BLE scan failed (Android code " + errorCode + "). Classic discovery is still available.") }
            }
        }
        scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE scanning is unavailable. Check Bluetooth is on." }
        scanCallback = scan
        scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        mutable.update { it.copy(scanning = true) }
        val advertise = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (epoch == generation) mutable.update { it.copy(advertising = true) }
            }
            override fun onStartFailure(errorCode: Int) {
                if (epoch == generation) mutable.update { it.copy(advertising = false, detail = "BLE advertising failed (Android code " + errorCode + "). Scanning/Classic remain available.") }
            }
        }
        advertiser = adapter.bluetoothLeAdvertiser
        advertiseCallback = advertise
        if (advertiser != null && adapter.isMultipleAdvertisementSupported) {
            var label = adapter.name ?: "iTantra"
            while (label.toByteArray(Charsets.UTF_8).size > 12) label = label.dropLast(1)
            advertiser?.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(false).setTimeout(120000).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
                AdvertiseData.Builder().addServiceUuid(uuid).build(),
                AdvertiseData.Builder().addServiceData(uuid, label.toByteArray(Charsets.UTF_8)).build(), advertise)
        } else mutable.update { it.copy(detail = "This phone can scan BLE but cannot advertise. Use Classic discovery to connect.") }
        scanExpiry = scope.launch { delay(30000); stopScan(); mutable.update { it.copy(scanning = false) } }
        expiry = scope.launch {
            delay(120000); stopAdvertising()
            mutable.update { it.copy(advertising = false, detail = "BLE session ended. Scan again to refresh nearby presence.") }
        }
    }
    private fun stopScan() {
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        scanner = null; scanCallback = null
    }
    private fun stopAdvertising() {
        advertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        advertiser = null; advertiseCallback = null
    }
    fun stop() {
        generation++
        expiry?.cancel(); expiry = null; scanExpiry?.cancel(); scanExpiry = null
        stopScan(); stopAdvertising()
        mutable.update { it.copy(scanning = false, advertising = false) }
    }
}
