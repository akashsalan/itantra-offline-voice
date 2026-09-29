package org.itantra.app.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RadioMode(val label: String, val protocol: String) {
    WIFI_DIRECT("Wi-Fi Direct", "wifi-direct-tcp"),
    BLUETOOTH("Bluetooth Classic", "bluetooth-classic-rfcomm"),
    SAME_WIFI("Same Wi-Fi", "lan-tls"),
    HOTSPOT("Phone Hotspot", "lan-tls"),
    WIFI_DIRECT_GROUP("Wi-Fi Direct", "wifi-direct-group-tls");
    val isLan: Boolean get() = this == SAME_WIFI || this == HOTSPOT || this == WIFI_DIRECT_GROUP
}
data class Peer(val id: String, val name: String, val detail: String = "iTantra service found")
sealed interface LinkState {
    data object Disconnected : LinkState
    data object Discovering : LinkState
    data class Connecting(val peer: Peer) : LinkState
    data class Connected(val peer: Peer) : LinkState
    data class Failed(val reason: String) : LinkState
}
data class SendResult(val bytes: Int)
interface LinkTransport {
    val displayName: String get() = "Wi-Fi Direct"
    val capabilities: List<String> get() = listOf("wifi-direct-tcp")
    val state: StateFlow<LinkState>
    suspend fun discover(): Flow<Peer>
    suspend fun connect(peer: Peer)
    suspend fun send(frame: ByteArray): SendResult
    fun incoming(): Flow<ByteArray>
    suspend fun disconnect()
}
