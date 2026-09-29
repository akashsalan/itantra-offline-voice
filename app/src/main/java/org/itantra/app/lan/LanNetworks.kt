package org.itantra.app.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.*

data class LanAddress(val ip: String, val prefix: Int, val broadcast: String?, val network: Network?)
data class LanNetworkStatus(val addresses: List<LanAddress> = emptyList()) {
    val ready get() = addresses.isNotEmpty()
    val detail get() = if (ready) "Local network ready · no internet needed" else "Join Wi-Fi or turn on this phone's hotspot to use groups."
}

/** Network routing is injectable for local-socket tests; production only exposes on-link Wi-Fi/hotspot addresses. */
interface LanNetworkAccess {
    fun inspect(): LanNetworkStatus
    fun matching(address: String): LanAddress
    fun accepts(address: String): Boolean = runCatching { matching(address); true }.getOrDefault(false)
    fun connect(address: String, port: Int): Socket
}

class LanNetworks(private val context: Context) : LanNetworkAccess {
    override fun inspect(): LanNetworkStatus {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            val properties = connectivity.getLinkProperties(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && properties?.interfaceName != null)
                properties.interfaceName!! to network else null
        }.toMap()
        val addresses = mutableListOf<LanAddress>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { device ->
            val name = device.name.lowercase()
            // ConnectivityManager does not expose a manually enabled hotspot as an upstream Network.
            val isWifi = device.name in wifi || Regex("^(wlan|swlan|wifi|ap|softap|br|tether)[a-z0-9_.-]*$").matches(name)
            if (device.isUp && !device.isLoopback && isWifi && !name.contains("p2p")) {
                device.interfaceAddresses.forEach { address ->
                    val ip = address.address as? Inet4Address
                    if (ip != null && LanRules.privateAddress(ip.hostAddress!!)) addresses += LanAddress(
                        ip.hostAddress!!, address.networkPrefixLength.toInt(), address.broadcast?.hostAddress, wifi[device.name])
                }
            }
        }
        return LanNetworkStatus(addresses.distinctBy { it.ip })
    }
    override fun matching(address: String): LanAddress = inspect().addresses.firstOrNull { LanRules.onLink(address, it.ip, it.prefix) }
        ?: error("Peer is not reachable on this Wi-Fi/hotspot subnet. Join the same network first.")
    override fun connect(address: String, port: Int): Socket {
        require(port in 1024..65535)
        val route = matching(address)
        val socket = Socket()
        try {
            route.network?.bindSocket(socket)
            socket.bind(InetSocketAddress(InetAddress.getByName(route.ip), 0))
            socket.connect(InetSocketAddress(InetAddress.getByName(address), port), 5000)
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }
}
