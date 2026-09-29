package org.itantra.app.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.protocol.lan.LanNotice
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class NearbyRoom(val id: String, val name: String, val host: String, val address: String,
    val port: Int, val group: Boolean, val members: Int, val seen: Long, val nsd: Boolean = false)

/** NSD plus small on-link UDP presence packets for hotspot implementations without working mDNS. */
class LanDiscovery(private val context: Context, private val scope: CoroutineScope, val networks: LanNetworkAccess,
    private val publishServices: Boolean = true) {
    private val mutableRooms = MutableStateFlow<List<NearbyRoom>>(emptyList())
    val rooms = mutableRooms.asStateFlow()
    private val mutableNetwork = MutableStateFlow(LanNetworkStatus())
    val network = mutableNetwork.asStateFlow()
    private val mutableDetail = MutableStateFlow("")
    val detail = mutableDetail.asStateFlow()
    private val found = ConcurrentHashMap<String, NearbyRoom>()
    private val serviceKeys = ConcurrentHashMap<String, String>()
    private val resolving = AtomicBoolean()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val discoveredServices = ConcurrentHashMap<String, NsdServiceInfo>()
    private val lifecycle = Mutex()
    @Volatile private var nsdEpoch = 0
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Volatile private var advertised: LanNotice? = null
    @Volatile private var browsing = false
    @Volatile private var socket: DatagramSocket? = null
    private var reader: Job? = null
    private var ticker: Job? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var lastQueryReply = 0L

    suspend fun browse(enabled: Boolean) = lifecycle.withLock {
        browsing = enabled
        if (enabled) { ensureStarted(); startNsd(); refreshNow() }
        else { stopNsd(); if (advertised == null) stopLocked() }
    }
    suspend fun advertise(notice: LanNotice?) = lifecycle.withLock {
        val changed = advertised != notice
        advertised = notice
        if (changed) unregister()
        if (notice != null) { ensureStarted(); if (publishServices && registration == null) register(notice); refreshNow() }
        else if (!browsing) stopLocked()
    }
    suspend fun refresh() = lifecycle.withLock { refreshNow() }
    private suspend fun refreshNow() = withContext(Dispatchers.IO) {
        val current = runCatching { networks.inspect() }.getOrDefault(LanNetworkStatus())
        mutableNetwork.value = current
        val now = SystemClock.elapsedRealtime()
        found.entries.removeIf { !networks.accepts(it.value.address) || now - it.value.seen > 20_000 }
        publish()
        if (current.ready && publishServices) {
            if (browsing && listener == null) startNsd()
            discoveredServices.values.forEach { enqueueResolve(it) }
            val query = LanNotice.newBuilder().setVersion(LanRules.VERSION).setQuery(true).build()
            current.addresses.forEach { address ->
                val destinations = listOfNotNull(address.broadcast, "255.255.255.255").distinct()
                destinations.forEach { target ->
                    if (browsing) send(query, address, target)
                    advertised?.let { send(it, address, target) }
                }
            }
        }
    }
    private suspend fun ensureStarted() = withContext(Dispatchers.IO) {
        mutableNetwork.value = networks.inspect()
        if (!publishServices) return@withContext
        if (ticker?.isActive == true) return@withContext
        runCatching {
            multicast = context.applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("itantra-local-discovery").apply { setReferenceCounted(false); acquire() }
        }
        try {
            val udp = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(LanRules.DISCOVERY_PORT)) }
            socket = udp
            reader = scope.launch(Dispatchers.IO) {
                while (isActive && !udp.isClosed) {
                    try {
                        val packet = DatagramPacket(ByteArray(2049), 2049)
                        udp.receive(packet)
                        if (packet.length > 2048) continue
                        val source = packet.address.hostAddress ?: continue
                        if (!networks.accepts(source)) continue
                        val notice = LanNotice.parseFrom(packet.data.copyOf(packet.length))
                        if (notice.version != LanRules.VERSION) continue
                        if (notice.query) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastQueryReply >= 1000) {
                                lastQueryReply = now
                                advertised?.let { send(it, networks.matching(source), source) }
                            }
                        } else if (browsing) accept(notice, source, false)
                    } catch (_: Exception) { if (udp.isClosed) break }
                }
            }
        } catch (_: Exception) { mutableDetail.value = "Hotspot discovery unavailable; using local service discovery or manual address." }
        ticker = scope.launch { while (isActive) { runCatching { refresh() }; delay(5000) } }
    }
    private fun send(notice: LanNotice, local: LanAddress, target: String) {
        runCatching {
            val bytes = notice.toByteArray()
            require(bytes.size <= 1024)
            DatagramSocket(null).use { udp ->
                udp.broadcast = true
                local.network?.bindSocket(udp)
                udp.bind(InetSocketAddress(InetAddress.getByName(local.ip), 0))
                udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(target), LanRules.DISCOVERY_PORT))
            }
        }
    }
    private fun accept(notice: LanNotice, address: String, isNsd: Boolean): String? {
        if (notice.roomId == advertised?.roomId || !LanRules.room(notice.roomId) || !networks.accepts(address) ||
            notice.port !in 1024..65535 || notice.members !in 0..LanRules.MAX_MEMBERS) return null
        val name = runCatching { LanRules.name(notice.roomName) }.getOrNull() ?: return null
        val host = runCatching { LanRules.name(notice.hostName) }.getOrNull() ?: return null
        val key = notice.roomId + "@" + address
        if (found.size >= 64 && !found.containsKey(key)) return null
        found[key] = NearbyRoom(notice.roomId, name, host, address, notice.port, notice.group, notice.members,
            SystemClock.elapsedRealtime(), isNsd || found[key]?.nsd == true)
        publish()
        return key
    }
    private fun publish() { mutableRooms.value = found.values.sortedWith(compareBy<NearbyRoom> { !it.group }.thenBy { it.name }).toList() }
    @Suppress("DEPRECATION")
    private fun startNsd() {
        if (!publishServices || listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                if (listener === this) listener = null
                mutableDetail.value = "Using hotspot discovery; local service discovery could not start ($code)."
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {
                if (listener !== this) return
                discoveredServices.remove(info.serviceName)
                serviceKeys.remove(info.serviceName)?.let(found::remove); publish()
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (listener !== this || !browsing || !info.serviceType.contains("_itantra-lan._tcp") || discoveredServices.size >= 64) return
                discoveredServices[info.serviceName] = info
                enqueueResolve(info)
            }
        }
        listener = discovery
        runCatching { nsd.discoverServices(LanRules.SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { listener = null; mutableDetail.value = "Using hotspot discovery. You can also join by local address." }
    }
    private fun enqueueResolve(info: NsdServiceInfo) {
        if (!browsing || resolveQueue.size >= 64 || resolveQueue.any { it.serviceName == info.serviceName }) return
        resolveQueue.offer(info); resolveNext()
    }
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!browsing || !resolving.compareAndSet(false, true)) return
        val info = resolveQueue.poll()
        if (info == null) { resolving.set(false); return }
        val generation = nsdEpoch
        fun finish() { if (generation == nsdEpoch) { resolving.set(false); resolveNext() } }
        runCatching { nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { finish() }
            override fun onServiceResolved(service: NsdServiceInfo) {
                try {
                    if (generation != nsdEpoch || !browsing || !discoveredServices.containsKey(info.serviceName)) return
                    fun field(key: String) = service.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
                    val ip = (service.host as? Inet4Address)?.hostAddress ?: return
                    val notice = LanNotice.newBuilder().setVersion(1).setRoomId(field("id")).setRoomName(field("name"))
                        .setHostName(field("host")).setGroup(field("group") == "1").setMembers(field("count").toIntOrNull() ?: 0).setPort(service.port).build()
                    accept(notice, ip, true)?.let { serviceKeys[service.serviceName] = it }
                } catch (_: Exception) { /* Invalid presence data must not crash the app. */ }
                finally { finish() }
            }
        }) }.onFailure { finish() }
    }
    private fun register(notice: LanNotice) {
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { mutableDetail.value = "Using hotspot discovery; service registration failed ($code)." }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        val service = NsdServiceInfo().apply {
            serviceName = "iTantra-" + notice.roomId.take(8); serviceType = LanRules.SERVICE; port = notice.port
            setAttribute("id", notice.roomId); setAttribute("name", notice.roomName); setAttribute("host", notice.hostName)
            setAttribute("group", if (notice.group) "1" else "0"); setAttribute("count", notice.members.toString())
        }
        registration = callback
        runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, callback) }.onFailure { registration = null }
    }
    private fun unregister() { registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null }
    private fun stopNsd() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        nsdEpoch++; resolveQueue.clear(); discoveredServices.clear(); resolving.set(false)
    }
    suspend fun stop() = lifecycle.withLock { stopLocked() }
    private fun stopLocked() {
        browsing = false; advertised = null; unregister(); stopNsd()
        ticker?.cancel(); ticker = null; reader?.cancel(); reader = null
        socket?.close(); socket = null
        multicast?.let { runCatching { if (it.isHeld) it.release() } }; multicast = null
        found.clear(); serviceKeys.clear(); publish()
    }
}
