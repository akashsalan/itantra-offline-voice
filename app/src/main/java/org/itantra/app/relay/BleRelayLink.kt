package org.itantra.app.relay

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.itantra.app.core.RelayFragments
import org.itantra.app.transport.BluetoothAccess
import java.util.UUID

/** Application-specific GATT relay; NOT Bluetooth SIG Mesh. One serialized outbound link.
 * Every device advertises a writable mailbox and discovers other mailboxes in short bursts. */
@SuppressLint("MissingPermission")
internal class BleRelayLink(private val context: Context, private val scope: CoroutineScope,
    private val received: (ByteArray, Int) -> Unit, private val nearby: (String, BluetoothDevice) -> Unit,
    private val problem: (String) -> Unit,
    private val serviceId: UUID = SERVICE, private val mailboxId: UUID = MAILBOX) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6c63d6b8-657d-4ac0-9e7e-6cc66479a401")
        val MAILBOX: UUID = UUID.fromString("6c63d6b8-657d-4ac0-9e7e-6cc66479a402")
    }
    private var server: BluetoothGattServer? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scan: ScanCallback? = null
    private var advertising: AdvertiseCallback? = null
    private var pulses: Job? = null
    private var client: BluetoothGatt? = null
    private var generation = 0
    private val assemblies = mutableMapOf<String, RelayFragments.Assembler>()
    suspend fun start(teamId: String) {
        stop()
        val adapter = BluetoothAccess.requireAdapter(context)
        check(adapter.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser != null) { "This phone cannot advertise a BLE relay. Use Wi-Fi Direct or Bluetooth Classic." }
        val epoch = ++generation
        val registered = CompletableDeferred<Boolean>()
        val mailbox = BluetoothGattCharacteristic(mailboxId, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) { if (service.uuid == serviceId) registered.complete(status == BluetoothGatt.GATT_SUCCESS) }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) synchronized(assemblies) { assemblies.remove(device.address) }
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
                var completed: ByteArray? = null
                var framedBytes = 0
                val accepted = runCatching {
                    check(epoch == generation && characteristic.uuid == mailboxId && !preparedWrite && offset == 0 && responseNeeded)
                    synchronized(assemblies) {
                        check(device.address in assemblies || assemblies.size < 8)
                        val assembler = assemblies.getOrPut(device.address) { RelayFragments.Assembler() }
                        completed = assembler.accept(value, SystemClock.elapsedRealtime()); framedBytes = assembler.framedBytes
                    }
                }.isSuccess
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null) }
                completed?.let { bytes -> scope.launch { if (epoch == generation) received(bytes, framedBytes) } }
            }
        }
        server = context.getSystemService(BluetoothManager::class.java).openGattServer(context, callback)
        check(server != null) { "Android could not open a BLE relay. Retry after turning Bluetooth on." }
        check(server!!.addService(BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(mailbox) }))
        check(withTimeout(8000) { registered.await() }) { "BLE mailbox setup failed." }
        val uuid = ParcelUuid(serviceId)
        val advertised = CompletableDeferred<Boolean>()
        advertising = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertised.complete(true) }
            override fun onStartFailure(errorCode: Int) { advertised.complete(false) }
        }
        advertiser = adapter.bluetoothLeAdvertiser
        advertiser!!.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
            AdvertiseData.Builder().addServiceUuid(uuid).build(),
            AdvertiseData.Builder().addServiceData(uuid, teamId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).build(), advertising)
        check(withTimeout(8000) { advertised.await() }) { "BLE advertising failed. This phone may not support concurrent Bluetooth modes." }
        scanner = adapter.bluetoothLeScanner
        check(scanner != null)
        scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (epoch != generation) return
                val tag = result.scanRecord?.getServiceData(uuid) ?: return
                if (org.itantra.app.core.RelayPacket.hex(tag) == teamId) scope.launch { if (epoch == generation) nearby(result.device.address, result.device) }
            }
            override fun onScanFailed(errorCode: Int) { scope.launch { if (epoch == generation) problem("BLE discovery failed. Stop relay and retry; delivery is not guaranteed.") } }
        }
        pulses = scope.launch {
            while (isActive && epoch == generation) {
                runCatching { scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scan) }
                    .onFailure { problem("Bluetooth discovery stopped. Check Bluetooth and permissions.") }
                delay(12000)
                runCatching { scanner?.stopScan(scan) }
                delay(18000)
            }
        }
    }
    private data class Event(val kind: String, val status: Int = 0, val mtu: Int = 23)
    suspend fun send(device: BluetoothDevice, frames: List<Pair<String, ByteArray>>, sent: (String, Int) -> Unit) {
        val epoch = generation
        val events = Channel<Event>(32)
        var mtu = 23
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(Event(if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "closed", status))
            }
            override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) { events.trySend(Event("mtu", status, value)) }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { events.trySend(Event("services", status)) }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { events.trySend(Event("write", status)) }
        }
        suspend fun await(kind: String): Event = withTimeout(8000) {
            var event: Event
            do { event = events.receive(); check(event.kind != "closed" && epoch == generation) } while (event.kind != kind)
            check(event.status == BluetoothGatt.GATT_SUCCESS); event
        }
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        client = gatt
        try {
            await("connected")
            if (gatt.requestMtu(247)) mtu = await("mtu").mtu
            check(gatt.discoverServices()); await("services")
            val characteristic = requireNotNull(gatt.getService(serviceId)?.getCharacteristic(mailboxId))
            for ((id, frame) in frames) {
                currentCoroutineContext().ensureActive(); check(epoch == generation)
                val parts = RelayFragments.split(frame, mtu, java.security.SecureRandom().nextInt(65536))
                for (chunk in parts) {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    @Suppress("DEPRECATION")
                    check(gatt.writeCharacteristic(characteristic)); await("write")
                }
                sent(id, parts.sumOf { it.size }) // Application framing, not radio/link-layer overhead.
            }
        } finally { runCatching { gatt.disconnect() }; gatt.close(); if (client === gatt) client = null; events.close() }
    }
    fun stop() {
        generation++; pulses?.cancel(); pulses = null
        runCatching { scanner?.stopScan(scan) }; scanner = null; scan = null
        runCatching { advertiser?.stopAdvertising(advertising) }; advertiser = null; advertising = null
        runCatching { client?.disconnect() }; runCatching { client?.close() }; client = null
        runCatching { server?.close() }; server = null
        synchronized(assemblies) { assemblies.clear() }
    }
}
