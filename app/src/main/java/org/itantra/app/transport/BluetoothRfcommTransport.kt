package org.itantra.app.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.app.protocol.Wire

/** Secure SDP-selected Classic sockets. No audio, Internet or hidden Bluetooth APIs. */
@SuppressLint("MissingPermission") // Entry points validate runtime grants.
class BluetoothRfcommTransport(private val context: Context, private val scope: CoroutineScope) : LinkTransport {
    override val displayName = "Bluetooth Classic"
    override val capabilities = listOf("bluetooth-classic-rfcomm")
    private val mutable = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state = mutable.asStateFlow()
    private val peers = MutableSharedFlow<Peer>(replay = 64)
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private val writing = Mutex()
    private val ownership = Any()
    @Volatile private var active = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var connecting: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var scanJob: Job? = null
    private var registered = false
    private var adapter: BluetoothAdapter? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(unused: Context, intent: Intent) {
            if (!active) return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                closeSockets()
                mutable.value = LinkState.Failed("Bluetooth is off. Enable it and start discovery again.")
                return
            }
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                runCatching { if (device.type != BluetoothDevice.DEVICE_TYPE_LE) show(device) }
            }
        }
    }
    private fun show(device: BluetoothDevice) {
        runCatching {
            peers.tryEmit(Peer(device.address, device.name?.take(80)?.ifBlank { null } ?: "Unnamed Bluetooth phone",
                if (device.bondState == BluetoothDevice.BOND_BONDED) "Paired · open iTantra on the other phone"
                else "Classic device · iTantra must be open on the other phone"))
        }
    }
    private fun initialize() {
        adapter = BluetoothAccess.requireAdapter(context)
        active = true
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
        }
        listen()
    }
    override suspend fun discover(): Flow<Peer> = withContext(Dispatchers.Main.immediate) {
        initialize()
        if (socket != null) return@withContext peers.asSharedFlow()
        mutable.value = LinkState.Discovering
        adapter?.bondedDevices?.forEach(::show)
        adapter?.cancelDiscovery()
        check(adapter?.startDiscovery() == true) { "Bluetooth discovery could not start. Wait a moment and retry." }
        scanJob?.cancel()
        scanJob = scope.launch { delay(20000); runCatching { adapter?.cancelDiscovery() } }
        peers.asSharedFlow()
    }
    private fun listen() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val listener = checkNotNull(adapter).listenUsingRfcommWithServiceRecord("iTantra", BluetoothAccess.RFCOMM_UUID)
                server = listener
                try {
                    while (isActive && active) {
                        val client = listener.accept()
                        val claimed = synchronized(ownership) {
                            if (socket == null && active) { socket = client; true } else false
                        }
                        if (!claimed) { client.close(); continue }
                        runCatching { adapter?.cancelDiscovery() }
                        try { communicate(client) }
                        catch (error: Exception) {
                            if (isActive && active) mutable.value = LinkState.Failed("Bluetooth link lost. Listening for reconnection; queued messages are kept.")
                        }
                    }
                } finally { listener.close(); if (server === listener) server = null }
            } catch (error: Exception) {
                if (error !is CancellationException && active) mutable.value = LinkState.Failed(error.message ?: "Bluetooth server failed")
            }
        }
    }
    override suspend fun connect(peer: Peer) = withContext(Dispatchers.Main.immediate) {
        initialize()
        require(BluetoothAdapter.checkBluetoothAddress(peer.id)) { "Invalid Classic Bluetooth address" }
        check(socket == null && clientJob?.isActive != true) { "A connection is already active. Disconnect before changing peers." }
        runCatching { adapter?.cancelDiscovery() }
        mutable.value = LinkState.Connecting(peer)
        clientJob = scope.launch(Dispatchers.IO) {
            var completedOnce = false
            for (attempt in 0 until 4) {
                if (!isActive || !active || socket != null) break
                val client = checkNotNull(adapter).getRemoteDevice(peer.id).createRfcommSocketToServiceRecord(BluetoothAccess.RFCOMM_UUID)
                connecting = client
                val timeout = scope.launch { delay(25000); if (connecting === client) runCatching { client.close() } }
                try {
                    client.connect()
                    timeout.cancel(); connecting = null
                    val claimed = synchronized(ownership) {
                        if (socket == null && active) { socket = client; true } else false
                    }
                    if (!claimed) { client.close(); break }
                    completedOnce = true
                    communicate(client)
                } catch (error: Exception) {
                    if (!isActive || !active) break
                    // Never repeatedly prompt after a rejected initial pairing request.
                    if (!completedOnce || attempt == 3) {
                        mutable.value = LinkState.Failed("Bluetooth connection ended: " + (error.message ?: "peer unavailable") + ". Open Connections on both phones and reconnect.")
                        break
                    }
                    mutable.value = LinkState.Connecting(peer)
                    delay(listOf(1000L, 2000L, 4000L)[attempt.coerceAtMost(2)])
                } finally {
                    timeout.cancel()
                    if (connecting === client) connecting = null
                    runCatching { client.close() }
                }
            }
        }
    }
    private suspend fun communicate(client: BluetoothSocket) {
        val device = client.remoteDevice
        mutable.value = LinkState.Connected(Peer(device.address, device.name ?: "Nearby iTantra", "Secure RFCOMM"))
        try {
            while (currentCoroutineContext().isActive && active) frames.emit(Wire.read(client.inputStream))
        } finally {
            runCatching { client.close() }
            synchronized(ownership) { if (socket === client) socket = null }
        }
    }
    override suspend fun send(frame: ByteArray): SendResult = withContext(Dispatchers.IO) {
        writing.withLock {
            val client = checkNotNull(socket) { "Bluetooth is disconnected" }
            val timeout = scope.launch { delay(10000); runCatching { client.close() } }
            try { Wire.write(client.outputStream, frame); SendResult(frame.size + 4) }
            finally { timeout.cancel() }
        }
    }
    override fun incoming(): Flow<ByteArray> = frames.asSharedFlow()
    private fun closeSockets() {
        serverJob?.cancel(); serverJob = null
        clientJob?.cancel(); clientJob = null
        scanJob?.cancel(); scanJob = null
        runCatching { server?.close() }; server = null
        runCatching { connecting?.close() }; connecting = null
        runCatching { socket?.close() }; socket = null
    }
    override suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
        active = false; closeSockets()
        runCatching { adapter?.cancelDiscovery() }
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        mutable.value = LinkState.Disconnected
    }
}
