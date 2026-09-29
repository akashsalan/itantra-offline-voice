package org.itantra.app.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Switchable transport, with one owner for frames and connection state. */
class SelectedTransport(
    private val wifi: LinkTransport, private val bluetooth: LinkTransport, scope: CoroutineScope
) : LinkTransport {
    private val changing = Mutex()
    private val mutableMode = MutableStateFlow(RadioMode.WIFI_DIRECT)
    val mode = mutableMode.asStateFlow()
    private val mutable = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state = mutable.asStateFlow()
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private var transitioning = false
    private val selected get() = if (mode.value == RadioMode.WIFI_DIRECT) wifi else bluetooth
    override val displayName get() = mode.value.label
    override val capabilities = listOf("wifi-direct-tcp", "bluetooth-classic-rfcomm", "ble-discovery")
    init {
        listOf(wifi, bluetooth).forEach { source ->
            scope.launch { source.state.collect { if (!transitioning && selected === source) mutable.value = it } }
            scope.launch { source.incoming().collect { if (!transitioning && selected === source) frames.emit(it) } }
        }
    }
    suspend fun select(mode: RadioMode) = changing.withLock {
        require(!mode.isLan) { "LAN conversations use the multi-peer session layer" }
        if (this.mode.value == mode) return@withLock
        transitioning = true
        try {
            selected.disconnect()
            mutable.value = LinkState.Disconnected
            mutableMode.value = mode
            mutable.value = selected.state.value
        } finally { transitioning = false }
    }
    override suspend fun discover(): Flow<Peer> = changing.withLock { selected.discover() }
    override suspend fun connect(peer: Peer) = changing.withLock { selected.connect(peer) }
    override suspend fun send(frame: ByteArray): SendResult = selected.send(frame)
    override fun incoming(): Flow<ByteArray> = frames.asSharedFlow()
    override suspend fun disconnect() = changing.withLock {
        selected.disconnect()
        mutable.value = LinkState.Disconnected
    }
}
