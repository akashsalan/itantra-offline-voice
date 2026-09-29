package org.itantra.app.transport

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.itantra.app.core.PermissionBoundary
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DirectGroupPeer(val address: String, val phone: String, val groupName: String, val roomId: String = "")
data class DirectGroupState(
    val active: Boolean = false, val discovering: Boolean = false, val formed: Boolean = false,
    val owner: Boolean = false, val ownerAddress: String = "", val interfaceName: String = "",
    val peers: List<DirectGroupPeer> = emptyList(), val detail: String = "Create or find a group. No router or hotspot needed."
)

/** Radio formation only. The existing LAN TLS session still owns admission and messages.
 * Separate from the legacy one-peer WifiDirectTransport; never owns its socket/channel. */
class WifiDirectGroups(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private val mutable = MutableStateFlow(DirectGroupState())
    val state = mutable.asStateFlow()
    val supported get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    private var registered = false
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var advertisement: WifiP2pDnsSdServiceInfo? = null
    private var generation = 0
    private var timeout: Job? = null
    private val permissionBoundary = PermissionBoundary({
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }, ::permissionLost)
    private fun requirePermission() = permissionBoundary.requireGranted()
    private fun permissionLost(): IllegalStateException {
        generation++ // Ignore callbacks from requests issued before revocation.
        mutable.update { it.copy(active = false, formed = false, owner = false, ownerAddress = "", interfaceName = "",
            discovering = false, peers = emptyList(), detail = "Nearby permission is missing or was revoked. Allow Nearby devices (Location on older Android), then retry.") }
        return IllegalStateException(state.value.detail)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(unused: Context, intent: Intent) {
            if (!state.value.active) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refresh()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (
                    intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED
                ) mutable.update { it.copy(formed = false, interfaceName = "", discovering = false, detail = "Wi-Fi is off. Enable it and try again.") }
            }
        }
    }

    private fun initialize() {
        check(supported) { "Wi-Fi Direct is unavailable on this phone. Use a phone hotspot or Bluetooth one-to-one." }
        requirePermission()
        check(context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled) { "Enable Wi-Fi. No internet connection is needed." }
        if (Build.VERSION.SDK_INT <= 32) {
            val enabled = if (Build.VERSION.SDK_INT >= 28) context.getSystemService(LocationManager::class.java).isLocationEnabled
                else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            check(enabled) { "Android requires Location mode for discovery. iTantra does not collect your location." }
        }
        if (channel == null) channel = checkNotNull(manager).initialize(context, Looper.getMainLooper()) {
            generation++; channel = null
            mutable.value = DirectGroupState(detail = "Wi-Fi Direct stopped. Create or find the group again.")
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
        }
        mutable.update { it.copy(active = true) }
    }

    /** Same contract as WifiDirectTransport.action: a timeout is a failure, not a cancellation. */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) {
        val done = withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<Unit> { pending -> block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (pending.isActive) pending.resume(Unit) }
                override fun onFailure(reason: Int) {
                    val text = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "This phone does not support Wi-Fi Direct groups. Use a phone hotspot instead."
                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy with another request. Wait a few seconds and try again."
                        else -> "Wi-Fi Direct is still closing the last connection. Wait a few seconds and try again."
                    }
                    if (pending.isActive) pending.resumeWithException(WifiDirectFailure(reason, text))
                }
            }) }
        }
        if (done == null) throw WifiDirectFailure(WifiDirectFailure.TIMEOUT,
            "Wi-Fi Direct did not respond. Try again, or turn Wi-Fi off and on.")
    }

    /**
     * The radio has one P2P group for the whole phone, shared by every channel. A
     * group left behind by the one-to-one transport, or by a link that dropped,
     * makes createGroup and connect fail with ERROR (0). Clear it first.
     */
    private suspend fun clearStaleGroup() {
        val p2p = checkNotNull(manager); val ch = checkNotNull(channel)
        runCatching { action { p2p.cancelConnect(ch, it) } }
        runCatching { action { p2p.removeGroup(ch, it) } }
    }

    private fun refresh() {
        val p2p = manager ?: return
        val ch = channel ?: return
        val epoch = generation
        try { p2p.requestConnectionInfo(ch) { info ->
            if (epoch != generation || !state.value.active) return@requestConnectionInfo
            // This callback runs after the outer request returns. It needs its own
            // permission boundary; an outer runCatching cannot protect it.
            try {
                requirePermission()
                if (!info.groupFormed || info.groupOwnerAddress == null) {
                    mutable.update { it.copy(formed = false, owner = false, ownerAddress = "", interfaceName = "") }
                } else permissionBoundary.call { p2p.requestGroupInfo(ch) { group ->
                    if (epoch != generation || !state.value.active) return@requestGroupInfo
                    mutable.update { it.copy(formed = true, owner = info.isGroupOwner,
                        ownerAddress = info.groupOwnerAddress.hostAddress.orEmpty(), interfaceName = group?.`interface`.orEmpty(),
                        detail = if (info.isGroupOwner) "Direct group available · keep this phone nearby" else "Direct radio connected · checking group identity") }
                } }
            } catch (_: SecurityException) { permissionLost() }
              catch (_: IllegalStateException) { permissionLost() }
        } } catch (_: SecurityException) { permissionLost() }
    }

    suspend fun create() = withContext(Dispatchers.Main.immediate) {
        initialize()
        check(!state.value.formed) { "Leave the current direct group first." }
        mutable.update { it.copy(detail = "Creating a direct group…") }
        clearStaleGroup()
        try { requirePermission(); action { checkNotNull(manager).createGroup(checkNotNull(channel), it) } }
        catch (_: SecurityException) { throw permissionLost() }
        refresh()
        // OrNull: a timeout from withTimeout is a cancellation, which callers rethrow
        // without cleaning up or telling the user anything.
        val connected = withTimeoutOrNull(25000) { state.first { !it.active || (it.formed && it.interfaceName.isNotBlank()) } }
            ?: throw WifiDirectFailure(WifiDirectFailure.TIMEOUT, "The direct group did not start. Turn Wi-Fi off and on, then create it again.")
        check(connected.active) { connected.detail }
        check(connected.owner) { "This phone did not become the group creator. Leave and retry." }
    }

    suspend fun advertise(name: String, roomId: String) = withContext(Dispatchers.Main.immediate) {
        check(state.value.formed && state.value.owner)
        val p2p = checkNotNull(manager); val ch = checkNotNull(channel)
        advertisement?.let { old -> action { p2p.removeLocalService(ch, old, it) } }
        val service = WifiP2pDnsSdServiceInfo.newInstance("iTantraGroup", "_itantra-group._tcp",
            mapOf("kind" to "group", "name" to name.take(48), "room" to roomId, "version" to "1"))
        try { requirePermission(); action { p2p.addLocalService(ch, service, it) } }
        catch (_: SecurityException) { throw permissionLost() }
        advertisement = service
    }

    suspend fun discover() = withContext(Dispatchers.Main.immediate) {
        initialize()
        check(!state.value.formed) { "Leave the current group before finding another." }
        val p2p = checkNotNull(manager); val ch = checkNotNull(channel)
        val epoch = generation
        fun accept(device: WifiP2pDevice, name: String, room: String) {
            if (epoch != generation || !state.value.discovering) return
            val peer = DirectGroupPeer(device.deviceAddress, device.deviceName.ifBlank { "Nearby phone" }, name.take(48), room.take(64))
            mutable.update { it.copy(peers = (it.peers.filterNot { old -> old.address == peer.address } + peer).takeLast(32)) }
        }
        p2p.setDnsSdResponseListeners(ch, { instance, type, device ->
            if (instance.equals("iTantraGroup", true) && type.startsWith("_itantra-group._tcp"))
                accept(device, "Group on ${device.deviceName.ifBlank { "nearby phone" }}", "")
        }, { domain, values, device ->
            if (domain.startsWith("iTantraGroup.", true) && values["kind"] == "group" && values["version"] == "1")
                accept(device, values["name"].orEmpty().ifBlank { "Nearby group" }, values["room"].orEmpty())
        })
        request?.let { old -> action { p2p.removeServiceRequest(ch, old, it) } }
        val fresh = WifiP2pDnsSdServiceRequest.newInstance("_itantra-group._tcp")
        action { p2p.addServiceRequest(ch, fresh, it) }; request = fresh
        mutable.update { it.copy(discovering = true, peers = emptyList(), detail = "Finding direct groups… keep the creator's app open") }
        try { requirePermission(); action { p2p.discoverServices(ch, it) } }
        catch (_: SecurityException) { throw permissionLost() }
        timeout?.cancel(); timeout = scope.launch { delay(30000); timeout = null; stopDiscovery() }
    }

    suspend fun join(peer: DirectGroupPeer): String = withContext(Dispatchers.Main.immediate) {
        initialize()
        require(peer.address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
        check(!state.value.formed) { "Leave the current group first." }
        stopDiscovery()
        mutable.update { it.copy(detail = "Connecting to ${peer.groupName}… accept Android's connection prompt") }
        val config = WifiP2pConfig().apply { deviceAddress = peer.address; groupOwnerIntent = 0; wps.setup = WpsInfo.PBC }
        clearStaleGroup()
        var attempt = 0
        while (true) {
            try { requirePermission(); action { checkNotNull(manager).connect(checkNotNull(channel), config, it) }; break }
            catch (_: SecurityException) { throw permissionLost() }
            catch (failure: WifiDirectFailure) {
                // Transient refusals right after a group ends; retry a couple of times.
                if (!failure.retryable || ++attempt >= 3) throw failure
                delay(1500L * attempt)
            }
        }
        refresh()
        val formed = withTimeoutOrNull(35000) { state.first { !it.active || (it.formed && it.interfaceName.isNotBlank()) } }
            ?: throw WifiDirectFailure(WifiDirectFailure.TIMEOUT, "Could not join ${peer.groupName}. Accept Android's prompt on both phones, or ask the creator to keep the app open.")
        check(formed.active) { formed.detail }
        check(!formed.owner) { "The other phone must create the group first. Leave and ask it to create one." }
        formed.ownerAddress
    }

    suspend fun stopDiscovery() = withContext(Dispatchers.Main.immediate) {
        timeout?.cancel(); timeout = null
        if (!state.value.active) return@withContext
        val p2p = manager; val ch = channel
        if (p2p != null && ch != null) {
            runCatching { action { p2p.stopPeerDiscovery(ch, it) } }
            request?.let { old -> runCatching { action { p2p.removeServiceRequest(ch, old, it) } } }; request = null
        }
        mutable.update { it.copy(discovering = false) }
    }

    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        val wasActive = state.value.active
        generation++; stopDiscovery()
        val p2p = manager; val ch = channel
        if (wasActive && p2p != null && ch != null) {
            advertisement?.let { old -> runCatching { action { p2p.removeLocalService(ch, old, it) } } }
            runCatching { action { p2p.cancelConnect(ch, it) } }
            runCatching { action { p2p.removeGroup(ch, it) } }
        }
        advertisement = null
        if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false }
        mutable.value = DirectGroupState()
    }
}
