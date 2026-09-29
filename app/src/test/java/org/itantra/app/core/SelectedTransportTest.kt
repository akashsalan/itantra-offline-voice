package org.itantra.app.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.itantra.app.transport.*
import org.junit.Assert.*
import org.junit.Test

class SelectedTransportTest {
    private class Link : LinkTransport {
        val mutable = MutableStateFlow<LinkState>(LinkState.Disconnected)
        override val state = mutable.asStateFlow()
        val frames = MutableSharedFlow<ByteArray>()
        var sends = 0
        var disconnects = 0
        override fun incoming(): Flow<ByteArray> = frames
        override suspend fun discover(): Flow<Peer> = emptyFlow()
        override suspend fun connect(peer: Peer) { mutable.value = LinkState.Connected(peer) }
        override suspend fun send(frame: ByteArray): SendResult { sends++; return SendResult(frame.size) }
        override suspend fun disconnect() { disconnects++; mutable.value = LinkState.Disconnected }
    }
    @Test fun switchingDisconnectsOldRadioAndRoutesOnlySelectedFrames() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val wifi = Link(); val bt = Link()
        try {
            val selected = SelectedTransport(wifi, bt, scope)
            val received = mutableListOf<Int>()
            scope.launch { selected.incoming().collect { received += it[0].toInt() } }
            wifi.connect(Peer("wifi", "Wi-Fi peer"))
            assertTrue(selected.state.value is LinkState.Connected)
            selected.send(byteArrayOf(1)); assertEquals(1, wifi.sends)
            selected.select(RadioMode.BLUETOOTH)
            assertEquals(1, wifi.disconnects)
            assertEquals(LinkState.Disconnected, selected.state.value)
            wifi.mutable.value = LinkState.Failed("Late old callback")
            assertEquals(LinkState.Disconnected, selected.state.value)
            wifi.frames.emit(byteArrayOf(7))
            bt.connect(Peer("bt", "Bluetooth peer"))
            bt.frames.emit(byteArrayOf(8))
            selected.send(byteArrayOf(2))
            assertEquals(listOf(8), received)
            assertEquals(1, bt.sends)
            assertEquals("Bluetooth Classic", selected.displayName)
        } finally { job.cancelAndJoin() }
    }
}
