package org.itantra.app.transport

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.app.protocol.Wire
import java.net.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A failed Wi-Fi Direct framework call, keeping the reason code so callers can
 * tell a transient refusal (worth retrying) from a permanent one.
 */
class WifiDirectFailure(val reason: Int, message: String) : IllegalStateException(message) {
    /** ERROR and BUSY are the framework still settling from a previous link. */
    val retryable get() = reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY || reason == TIMEOUT
    companion object { const val TIMEOUT = -1 }
}

/** Only local Wi-Fi P2P sockets. No DNS, WAN endpoint or audio payload. */
@SuppressLint("MissingPermission") // All entry points pass checkAvailable(); broadcasts are active only after that.
class WifiDirectTransport(private val context: Context, private val scope: CoroutineScope) : LinkTransport {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private val mutable = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state = mutable.asStateFlow()
    private val peers = MutableSharedFlow<Peer>(replay = 64)
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private val writing = Mutex()
    private var registered = false
    private var active = false
    private var socketJob: Job? = null
    private var connectionTimeout: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    private var selected: Peer? = null
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var advertised: WifiP2pDnsSdServiceInfo? = null
    private var lastOwner: InetAddress? = null
    private var lastRole: Boolean? = null
    val supported: Boolean get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(unused: Context, intent: Intent) {
            if (!active) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        closeSockets(); mutable.value = LinkState.Failed("Wi-Fi Direct is off. Enable Wi-Fi and discover again.")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnection()
            }
        }
    }
    private fun checkAvailable() {
        check(supported) { "This device does not provide Wi-Fi Direct" }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { "Nearby-device permission is required (Location on Android 12 and earlier)." }
        check(context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled) { "Enable Wi-Fi. Mobile data/internet may remain off." }
        if (Build.VERSION.SDK_INT <= 32) {
            val locationEnabled = if (Build.VERSION.SDK_INT >= 28) context.getSystemService(LocationManager::class.java).isLocationEnabled
                else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            check(locationEnabled) { "Android requires Location mode for Wi-Fi Direct discovery. No location coordinates are collected." }
        }
    }
    private fun initialize() {
        checkAvailable()
        if (channel == null) channel = checkNotNull(manager).initialize(context, Looper.getMainLooper()) {
            closeSockets(); channel = null
            // The service registration and request belonged to the dead channel.
            // Keeping them made discover() skip re-registering on the new one, so the
            // phone silently stopped being visible to others.
            advertised = null; request = null
            reconnectJob?.cancel(); reconnectJob = null
            mutable.value = LinkState.Failed("Wi-Fi Direct restarted on this phone. Search for nearby phones again.")
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
        }
        active = true
    }
    /**
     * One framework call, awaited.
     *
     * Uses withTimeoutOrNull rather than withTimeout: a timeout from withTimeout is a
     * CancellationException, which skips every ordinary failure path and left the
     * link showing "Connecting" forever. A timeout is a normal, retryable failure.
     */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) {
        val done = withTimeoutOrNull(6000) { suspendCancellableCoroutine<Unit> { continuation ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resumeWithException(WifiDirectFailure(reason, explain(reason)))
                }
            })
        } }
        if (done == null) throw WifiDirectFailure(WifiDirectFailure.TIMEOUT, explain(WifiDirectFailure.TIMEOUT))
    }

    /** What the user should do, not the framework's reason code. */
    private fun explain(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "This phone does not support Wi-Fi Direct. Try Bluetooth Classic."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy with another request. Wait a few seconds and try again."
        WifiP2pManager.NO_SERVICE_REQUESTS -> "Nearby search was reset. Search for nearby phones again."
        WifiDirectFailure.TIMEOUT -> "Wi-Fi Direct did not respond. Try again, or turn Wi-Fi off and on."
        // ERROR (0) on a reconnect is almost always the last link still closing.
        else -> "Wi-Fi Direct is still closing the last connection. Wait a few seconds, then tap the phone again."
    }
    override suspend fun discover(): Flow<Peer> = withContext(Dispatchers.Main.immediate) {
        initialize()
        if (socket != null) return@withContext peers.asSharedFlow()
        mutable.value = LinkState.Discovering
        val p2p = checkNotNull(manager)
        val ch = checkNotNull(channel)
        p2p.setDnsSdResponseListeners(ch, { instance, type, device ->
            if (instance.equals("iTantra", true) && type.startsWith(Wire.SERVICE, true)) {
                peers.tryEmit(Peer(device.deviceAddress, device.deviceName.ifBlank { "Nearby iTantra" }))
            }
        }, { _, _, _ -> })
        if (advertised == null) {
            val info = WifiP2pDnsSdServiceInfo.newInstance("iTantra", Wire.SERVICE, mapOf("protocol" to "1", "port" to Wire.PORT.toString()))
            action { p2p.addLocalService(ch, info, it) }; advertised = info
        }
        request?.let { old -> action { p2p.removeServiceRequest(ch, old, it) } }
        val fresh = WifiP2pDnsSdServiceRequest.newInstance()
        action { p2p.addServiceRequest(ch, fresh, it) }; request = fresh
        action { p2p.discoverServices(ch, it) }
        refreshConnection()
        peers.asSharedFlow()
    }
    override suspend fun connect(peer: Peer) = withContext(Dispatchers.Main.immediate) {
        initialize()
        // Only a live link blocks a new one. A dropped link can leave a socket
        // retry loop running; invite() clears that rather than refusing the tap.
        check(mutable.value !is LinkState.Connected) { "Disconnect the current peer before selecting another" }
        require(peer.id.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "Invalid Wi-Fi Direct peer address" }
        reconnectJob?.cancel(); reconnectJob = null
        selected = peer
        mutable.value = LinkState.Connecting(peer)
        try {
            invite(peer)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Previously the state stayed Connecting after a failed call, and the
            // timeout that would have cleared it had not been started yet.
            mutable.value = LinkState.Failed(error.message ?: "Could not connect to ${peer.name}.")
            throw error
        }
        armTimeout()
    }

    /**
     * The reconnect sequence. Every step is awaited before the next, because the
     * framework rejects a connect with ERROR or BUSY while any of them is pending.
     *
     * 1. Clear what the last link left behind: sockets, a pending invitation, a
     *    formed group, and running discovery.
     * 2. Rediscover the peer. Android empties its peer table when a group ends, and
     *    connect() to an address not in that table fails with ERROR (0) — the
     *    "framework error 0" users saw on every reconnect.
     * 3. Connect, retrying transient refusals with backoff.
     */
    private suspend fun invite(peer: Peer) {
        clearStaleLink()
        check(peerVisible(peer.id)) {
            "${peer.name} is not visible right now. Open Connections on that phone, then tap it again."
        }
        val config = WifiP2pConfig().apply { deviceAddress = peer.id; wps.setup = WpsInfo.PBC }
        var attempt = 0
        while (true) {
            try { action { checkNotNull(manager).connect(checkNotNull(channel), config, it) }; return }
            catch (failure: WifiDirectFailure) {
                if (!failure.retryable || ++attempt >= CONNECT_ATTEMPTS) throw failure
                runCatching { action { checkNotNull(manager).cancelConnect(checkNotNull(channel), it) } }
                delay(1500L * attempt)
            }
        }
    }

    private suspend fun clearStaleLink() {
        connectionTimeout?.cancel(); connectionTimeout = null
        // Cleared first, so the broadcast removeGroup triggers is not read as a
        // fresh "peer disconnected" and does not start an automatic reconnect.
        closeSockets(); lastOwner = null; lastRole = null
        val p2p = checkNotNull(manager); val ch = checkNotNull(channel)
        runCatching { action { p2p.cancelConnect(ch, it) } }
        if (groupPresent()) runCatching { action { p2p.removeGroup(ch, it) } }
        runCatching { action { p2p.stopPeerDiscovery(ch, it) } }
    }

    private suspend fun groupPresent(): Boolean = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            checkNotNull(manager).requestGroupInfo(checkNotNull(channel)) { group ->
                if (continuation.isActive) continuation.resume(group != null)
            }
        }
    } ?: false

    /** Starts peer discovery and waits until the framework lists [address]. */
    private suspend fun peerVisible(address: String): Boolean {
        val p2p = checkNotNull(manager); val ch = checkNotNull(channel)
        runCatching { action { p2p.discoverPeers(ch, it) } }
        val deadline = SystemClock.elapsedRealtime() + PEER_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val listed = withTimeoutOrNull(2000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    p2p.requestPeers(ch) { list ->
                        if (continuation.isActive) continuation.resume(
                            list?.deviceList?.any { it.deviceAddress.equals(address, ignoreCase = true) } == true
                        )
                    }
                }
            } ?: false
            if (listed) return true
            delay(700)
        }
        return false
    }

    /** Puts this phone back into discoverable state. Quiet: nobody asked for it. */
    private fun relisten() {
        val p2p = manager ?: return
        scope.launch(Dispatchers.Main.immediate) {
            val ch = channel ?: return@launch
            runCatching { action { p2p.discoverPeers(ch, it) } }
            request?.let { runCatching { action { p2p.discoverServices(ch, it) } } }
        }
    }

    private fun armTimeout() {
        connectionTimeout?.cancel()
        connectionTimeout = scope.launch(Dispatchers.Main.immediate) {
            delay(30000)
            if (mutable.value is LinkState.Connecting && socketJob?.isActive != true) {
                runCatching { action { checkNotNull(manager).cancelConnect(checkNotNull(channel), it) } }
                mutable.value = LinkState.Failed("Connection timed out. Keep Connections open on both phones, accept Android's prompt, or try Bluetooth Classic.")
            }
        }
    }

    /**
     * After an unexpected drop, the phone that started the link tries to restore it.
     *
     * Only the initiator does this ([selected] is set only by connect()). If both
     * phones re-invited at once their invitations would collide and both would get
     * BUSY, so the other phone simply waits to be invited again.
     */
    private fun scheduleReconnect(peer: Peer) {
        reconnectJob?.cancel()
        mutable.value = LinkState.Connecting(peer)
        reconnectJob = scope.launch(Dispatchers.Main.immediate) {
            for (attempt in 1..RECONNECT_ATTEMPTS) {
                delay(2000L * attempt)
                if (!active || selected != peer) return@launch
                try { invite(peer); armTimeout(); return@launch }
                catch (error: Exception) { if (error is CancellationException) throw error }
            }
            if (active && selected == peer)
                mutable.value = LinkState.Failed("Lost ${peer.name}. Tap it again to reconnect. Queued messages are kept.")
        }
    }
    private fun refreshConnection() {
        val p2p = manager ?: return
        val ch = channel ?: return
        try {
            // The framework invokes this listener later, on the main thread. The
            // enclosing try does not cover it, so guard the body itself: an OEM
            // Wi-Fi Direct stack throwing here would otherwise close the app.
            p2p.requestConnectionInfo(ch) { info ->
                if (!active) return@requestConnectionInfo
                try {
                    if (info == null || !info.groupFormed || info.groupOwnerAddress == null) {
                        if (lastOwner != null) {
                            closeSockets(); lastOwner = null; lastRole = null
                            val peer = selected
                            if (peer != null) scheduleReconnect(peer)
                            else {
                                // The other phone will re-invite us. It can only find
                                // us if we are listening, and Android stops that when
                                // the group ends.
                                relisten()
                                mutable.value = LinkState.Failed("Peer disconnected. Waiting for them to reconnect; queued messages are kept.")
                            }
                        }
                    } else {
                        if (lastOwner == info.groupOwnerAddress && lastRole == info.isGroupOwner && socketJob?.isActive == true) return@requestConnectionInfo
                        closeSockets()
                        lastOwner = info.groupOwnerAddress; lastRole = info.isGroupOwner
                        socketJob = scope.launch(Dispatchers.IO) { runGroup(info.groupOwnerAddress, info.isGroupOwner) }
                    }
                } catch (error: Exception) {
                    closeSockets()
                    mutable.value = LinkState.Failed(
                        "Wi-Fi Direct reported an error on this phone. Try Bluetooth Classic or a shared Wi-Fi network."
                    )
                }
            }
        } catch (error: SecurityException) { closeSockets(); mutable.value = LinkState.Failed("Nearby permission was revoked.") }
        catch (error: Exception) {
            closeSockets()
            mutable.value = LinkState.Failed("Wi-Fi Direct is unavailable on this phone right now.")
        }
    }
    private suspend fun runGroup(owner: InetAddress, isOwner: Boolean) {
        try {
            if (isOwner) {
                val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(owner, Wire.PORT)) }
                server = listener
                while (currentCoroutineContext().isActive && active) {
                    mutable.value = LinkState.Connecting(selected ?: Peer("p2p-group", "Waiting for peer app"))
                    val accepted = listener.accept()
                    try { communicate(accepted) } catch (error: Exception) {
                        if (currentCoroutineContext().isActive && active) mutable.value = LinkState.Failed("Link interrupted; waiting for peer to reconnect.")
                    }
                }
            } else {
                var attempts = 0
                while (currentCoroutineContext().isActive && active && attempts < 12) {
                    val client = Socket()
                    try {
                        socket = client
                        client.connect(InetSocketAddress(owner, Wire.PORT), 4000)
                        attempts = 0
                        communicate(client)
                    } catch (error: Exception) {
                        client.close(); if (socket === client) socket = null
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        attempts++
                        mutable.value = LinkState.Connecting(selected ?: Peer("p2p-owner", "Reconnecting to peer"))
                        delay(minOf(attempts * 1000L, 4000L))
                    }
                }
                if (active) mutable.value = LinkState.Failed("Peer app did not answer. Open Nearby on both phones, then reconnect.")
            }
        } catch (error: Exception) {
            if (error !is CancellationException && active) mutable.value = LinkState.Failed(error.message ?: "Local socket failed")
        }
    }
    private suspend fun communicate(client: Socket) {
        connectionTimeout?.cancel(); connectionTimeout = null
        socket = client
        client.tcpNoDelay = true; client.keepAlive = true; client.soTimeout = 30000
        mutable.value = LinkState.Connected(selected ?: Peer("p2p-peer", "Nearby iTantra"))
        try {
            val input = client.getInputStream()
            while (currentCoroutineContext().isActive && active) frames.emit(Wire.read(input))
        } finally { client.close(); if (socket === client) socket = null }
    }
    override suspend fun send(frame: ByteArray): SendResult = withContext(Dispatchers.IO) {
        writing.withLock {
            val connection = checkNotNull(socket) { "Local socket is disconnected" }
            val timeout = scope.launch { delay(10000); runCatching { connection.close() } }
            try { Wire.write(connection.getOutputStream(), frame); SendResult(frame.size + 4) }
            finally { timeout.cancel() }
        }
    }
    override fun incoming(): Flow<ByteArray> = frames.asSharedFlow()
    private fun closeSockets() {
        socketJob?.cancel(); socketJob = null
        runCatching { socket?.close() }; socket = null
        runCatching { server?.close() }; server = null
    }
    override suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
        active = false
        // A deliberate disconnect must never be undone by an automatic reconnect.
        reconnectJob?.cancel(); reconnectJob = null
        connectionTimeout?.cancel(); connectionTimeout = null
        closeSockets()
        val p2p = manager; val ch = channel
        if (p2p != null && ch != null) {
            runCatching { action { p2p.stopPeerDiscovery(ch, it) } }
            runCatching { action { p2p.cancelConnect(ch, it) } }
            runCatching { action { p2p.removeGroup(ch, it) } }
            runCatching { action { p2p.clearLocalServices(ch, it) } }
            runCatching { action { p2p.clearServiceRequests(ch, it) } }
        }
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        advertised = null; request = null; selected = null; lastOwner = null; lastRole = null
        mutable.value = LinkState.Disconnected
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RECONNECT_ATTEMPTS = 2
        const val PEER_WAIT_MS = 10_000L
    }
}
