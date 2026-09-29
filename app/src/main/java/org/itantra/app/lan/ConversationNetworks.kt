package org.itantra.app.lan

import org.itantra.app.transport.WifiDirectGroups
import java.net.*

/** Select exactly one network family; never send group data via the default internet route. */
class ConversationNetworks(
    private val local: LanNetworkAccess, private val direct: WifiDirectGroups,
    private val useDirect: () -> Boolean
) : LanNetworkAccess {
    override fun inspect(): LanNetworkStatus {
        if (!useDirect()) return local.inspect()
        val state = direct.state.value
        if (!state.formed || state.interfaceName.isBlank()) return LanNetworkStatus()
        val device = NetworkInterface.getByName(state.interfaceName) ?: return LanNetworkStatus()
        if (!device.isUp || device.isLoopback) return LanNetworkStatus()
        return LanNetworkStatus(device.interfaceAddresses.mapNotNull { address ->
            val ip = address.address as? Inet4Address ?: return@mapNotNull null
            val host = ip.hostAddress ?: return@mapNotNull null
            if (!LanRules.privateAddress(host) || !LanRules.onLink(state.ownerAddress, host, address.networkPrefixLength.toInt())) return@mapNotNull null
            LanAddress(host, address.networkPrefixLength.toInt(), address.broadcast?.hostAddress, null)
        })
    }
    override fun matching(address: String): LanAddress = inspect().addresses.firstOrNull { LanRules.onLink(address, it.ip, it.prefix) }
        ?: error("This phone is not connected to that local group. Rejoin the group and retry.")
    override fun connect(address: String, port: Int): Socket {
        if (!useDirect()) return local.connect(address, port)
        require(port in 1024..65535)
        val route = matching(address)
        val socket = Socket()
        try {
            socket.bind(InetSocketAddress(InetAddress.getByName(route.ip), 0))
            socket.connect(InetSocketAddress(address, port), 5000)
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }
}
