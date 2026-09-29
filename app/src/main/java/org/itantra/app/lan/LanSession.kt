package org.itantra.app.lan

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.UnicodeText
import org.itantra.app.core.PlaybackQueue
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import org.itantra.app.protocol.DeliveryPolicy
import org.itantra.app.protocol.Wire
import org.itantra.protocol.lan.*
import org.itantra.protocol.v1.Envelope
import org.itantra.protocol.v1.MessageType
import org.itantra.protocol.v1.TextOptionsBody
import java.net.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

data class LanRoomState(
    val active: Boolean = false, val hosting: Boolean = false, val connected: Boolean = false,
    val group: Boolean = true, val roomId: String = "", val name: String = "", val selfId: String = "",
    val hostId: String = "", val members: List<LanMember> = emptyList(), val accepting: Boolean = true,
    val confirmCode: String = "", val requests: List<LanMember> = emptyList(), val error: String? = null,
    val status: String = "Choose a device or create a named group."
) {
    val online get() = members.count { it.online }
    val ready get() = connected && online > 1
    val title get() = if (group) "$name · $online people" else members.firstOrNull { it.id != selfId }?.name ?: name
}

/** Multi-peer LAN sessions; transport and admission do not depend on ASR/TTS implementations. */
class LanSession(
    private val context: Context, private val scope: CoroutineScope, private val store: MessageStore,
    private val displayName: () -> String, val discovery: LanDiscovery, private val diagnostics: Diagnostics,
    private val received: (MessageEntity) -> Unit, private val tlsAlias: String = "itantra-lan-identity-v2",
    private val transmit: (SSLSocket, LanPacket) -> Unit = { socket, packet -> Wire.write(socket.outputStream, LanWire.encode(packet)) }
) {
    private val mutable = MutableStateFlow(LanRoomState())
    val state = mutable.asStateFlow()
    private val mutableSaved = MutableStateFlow("")
    val savedName = mutableSaved.asStateFlow()
    private val lock = Mutex()
    private val tls by lazy { LanTls(tlsAlias) }
    private val preferences = context.getSharedPreferences("itantra-lan-rooms-$tlsAlias", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val servers = mutableListOf<SSLServerSocket>()
    private val serverJobs = mutableListOf<Job>()
    private val pendingSockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val permits = Semaphore(12) // At most eight admitted devices plus four pending handshakes.
    private val members = linkedMapOf<String, LanMember>()
    private val clients = linkedMapOf<String, Connection>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val lastAdmission = ConcurrentHashMap<String, Long>()
    private var upstream: Connection? = null
    private var clientJob: Job? = null
    @Volatile private var confirmation: CompletableDeferred<String?>? = null
    private var secret: AdmissionSecret? = null
    @Volatile private var epoch = 0
    private var order = 0L
    private var localSession = randomId()
    private var hostedAddresses = emptySet<String>()
    private var transportLabel = "Same Wi-Fi"

    private inner class Connection(val socket: SSLSocket, val id: String) {
        private val measurementTransport = transportLabel
        private val output = Channel<LanPacket>(64)
        private val writer = scope.launch(Dispatchers.IO) {
            try { for (packet in output) {
                transmit(socket, packet)
                if (packet.kind == LanKind.DATA) {
                    val message = Wire.decode(packet.envelope.toByteArray())
                    diagnostics.transmitted(messageKey(packet.roomId, packet.senderId, message.messageId, "OUT"),
                        LanWire.encode(packet).size + 4, measurementTransport)
                }
            } }
            catch (_: Exception) { socket.close() }
        }
        fun send(packet: LanPacket) { if (!output.trySend(packet).isSuccess) { close(); error("Peer is too slow; pending messages are retained.") } }
        fun close() { output.close(); writer.cancel(); runCatching { socket.close() } }
    }
    init {
        scope.launch(Dispatchers.IO) {
            mutableSaved.value = runCatching { decode(preferences.getString("room", "").orEmpty()).roomName }.getOrDefault("")
        }
        scope.launch {
            while (isActive) {
                delay(250)
                try { lock.withLock { pump() } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(error = error.message ?: "Delivery paused") }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                lock.withLock {
                    if (!state.value.active) return@withLock
                    val ping = packet(LanKind.HEARTBEAT)
                    (clients.values.toList() + listOfNotNull(upstream)).forEach { runCatching { it.send(ping) } }
                    if (state.value.hosting) {
                        runCatching { maintainHosting() }.onFailure { error ->
                            mutable.update { it.copy(error = error.message, connected = false, status = "Local network unavailable · waiting") }
                        }
                    }
                }
            }
        }
    }
    private fun randomId(): Long { var value: Long; do { value = random.nextLong() } while (value == 0L); return value }
    private fun packet(kind: LanKind): LanPacket = LanPacket.newBuilder().setVersion(1).setKind(kind)
        .setRoomId(state.value.roomId).setRoomName(state.value.name).setGroup(state.value.group)
        .setHostId(state.value.hostId).setAccepting(state.value.accepting).build()
    private fun member(id: String, name: String, online: Boolean = true) = LanMember.newBuilder().setId(id).setName(LanRules.name(name)).setOnline(online).build()
    fun confirmHost(password: String) { confirmation?.complete(password) }
    fun approve(id: String, allow: Boolean) { approvals[id]?.complete(allow) }
    suspend fun setAccepting(value: Boolean) = lock.withLock {
        check(state.value.hosting)
        mutable.update { it.copy(accepting = value) }; roster()
    }
    suspend fun host(name: String, password: String, group: Boolean, label: String, resume: Boolean = false) = lock.withLock {
        val cleanName = if (resume) "" else LanRules.name(name)
        val credential = if (!resume && group) withContext(Dispatchers.Default) { AdmissionSecret.create(password) } else null
        val identity = withContext(Dispatchers.IO) { tls.id }
        val network = withContext(Dispatchers.IO) { discovery.networks.inspect() }
        check(network.ready) { "Join Wi-Fi or enable this phone's hotspot first. Internet is not required." }
        shutdown()
        transportLabel = label
        val saved = if (resume) decode(preferences.getString("room", "").orEmpty()) else null
        if (saved != null) {
            require(saved.hostId == identity && saved.group && LanRules.room(saved.roomId))
            secret = AdmissionSecret(Base64.decode(preferences.getString("salt", ""), Base64.NO_WRAP),
                Base64.decode(preferences.getString("verifier", ""), Base64.NO_WRAP))
            require(secret!!.salt.size == 16 && secret!!.verifier.size == 32)
        } else secret = credential
        members.clear()
        saved?.membersList?.forEach { if (LanRules.identity(it.id)) members[it.id] = it.toBuilder().setOnline(false).build() }
        members[identity] = member(identity, displayName())
        mutable.value = LanRoomState(active = true, hosting = true, connected = true, group = saved?.group ?: group,
            roomId = saved?.roomId ?: UUID.randomUUID().toString(), name = saved?.roomName ?: cleanName,
            selfId = identity, hostId = identity, members = members.values.toList(), status = "Hosting · keep iTantra running")
        order = store.messages.value.filter { it.roomId == state.value.roomId && it.lanPayload.isNotBlank() }
            .maxOfOrNull { runCatching { decode(it.lanPayload).roomOrder }.getOrDefault(0L) } ?: 0L
        try {
            bindServers(network.addresses)
            saveHosting(); advertise()
        } catch (error: Exception) { shutdown(); throw error }
    }
    private suspend fun bindServers(addresses: List<LanAddress>) {
        val currentEpoch = epoch
        val secureContext = withContext(Dispatchers.IO) { tls.context() }
        withContext(Dispatchers.IO) {
            addresses.forEach { address ->
                val server = secureContext.serverSocketFactory.createServerSocket() as SSLServerSocket
                try {
                    server.reuseAddress = true; server.needClientAuth = true
                    server.bind(InetSocketAddress(InetAddress.getByName(address.ip), LanRules.PORT))
                    servers += server
                    serverJobs += scope.launch(Dispatchers.IO) {
                        while (isActive && !server.isClosed && currentEpoch == epoch) {
                            try {
                                val socket = server.accept() as SSLSocket
                                val ip = socket.inetAddress.hostAddress.orEmpty()
                                if (!discovery.networks.accepts(ip) || !permits.tryAcquire()) { socket.close(); continue }
                                pendingSockets += socket
                                scope.launch(Dispatchers.IO) {
                                    try { accept(socket, currentEpoch) }
                                    finally { pendingSockets -= socket; permits.release(); runCatching { socket.close() } }
                                }
                            } catch (_: Exception) { if (server.isClosed) break }
                        }
                    }
                } catch (_: Exception) { server.close() }
            }
        }
        check(servers.isNotEmpty()) { "Could not open a local server on this Wi-Fi/hotspot." }
        hostedAddresses = addresses.map { it.ip }.toSet()
    }
    private suspend fun maintainHosting() {
        val network = withContext(Dispatchers.IO) { discovery.networks.inspect() }
        if (network.addresses.map { it.ip }.toSet() != hostedAddresses || servers.isEmpty()) {
            servers.forEach { runCatching { it.close() } }; servers.clear()
            serverJobs.forEach { it.cancel() }; serverJobs.clear()
            clients.values.forEach { it.close() }; clients.clear()
            pendingSockets.toList().forEach { runCatching { it.close() } }
            members.entries.forEach { if (it.key != state.value.selfId) it.setValue(it.value.toBuilder().setOnline(false).build()) }
            hostedAddresses = emptySet()
            mutable.update { it.copy(connected = false, members = members.values.toList(), status = "Wi-Fi changed · restoring hosting") }
            discovery.advertise(null)
            if (network.ready) {
                bindServers(network.addresses)
                mutable.update { it.copy(connected = true, error = null, status = "Hosting · ask members to rejoin if the address changed") }
            }
        }
        if (state.value.connected) advertise()
    }
    private suspend fun saveHosting() = withContext(Dispatchers.IO) {
        if (state.value.group && secret != null) {
            val saved = packet(LanKind.WELCOME).toBuilder().addAllMembers(members.values).build()
            check(preferences.edit().putString("room", encode(saved)).putString("salt", Base64.encodeToString(secret!!.salt, Base64.NO_WRAP))
                .putString("verifier", Base64.encodeToString(secret!!.verifier, Base64.NO_WRAP)).commit()) { "Could not save group configuration" }
            mutableSaved.value = state.value.name
        }
    }
    private suspend fun advertise() {
        val status = state.value
        discovery.advertise(LanNotice.newBuilder().setVersion(1).setRoomId(status.roomId).setRoomName(status.name)
            .setHostName(displayName()).setGroup(status.group).setPort(LanRules.PORT).setMembers(status.online).build())
    }
    private suspend fun roster() {
        mutable.update { it.copy(members = members.values.toList()) }
        val update = packet(LanKind.ROSTER).toBuilder().addAllMembers(members.values).build()
        clients.values.toList().forEach { runCatching { it.send(update) } }
        saveHosting()
    }

    private suspend fun accept(socket: SSLSocket, currentEpoch: Int) {
        var connection: Connection? = null
        var admitted = false
        val ip = socket.inetAddress.hostAddress.orEmpty()
        try {
            check(SystemClock.elapsedRealtime() >= (lastAdmission[ip] ?: 0)) { "Please wait before trying the password again." }
            LanTls.configure(socket); socket.useClientMode = false; socket.needClientAuth = true; socket.startHandshake()
            val id = LanTls.peerId(socket)
            val welcome = lock.withLock {
                check(epoch == currentEpoch && state.value.hosting)
                packet(LanKind.WELCOME)
            }
            Wire.write(socket.outputStream, LanWire.encode(welcome))
            socket.soTimeout = 120000 // User may be comparing the host code or entering a password.
            val join = LanWire.decode(Wire.read(socket.inputStream))
            require(join.kind == LanKind.JOIN && join.roomId == welcome.roomId && join.senderId == id)
            val person = member(id, join.senderName)
            val expected = lock.withLock {
                check(epoch == currentEpoch && state.value.hosting)
                check(state.value.accepting || id in members) { "The creator has paused new joins." }
                check(id != state.value.selfId && id !in clients) { "This device is already connected." }
                check(welcome.group || members.keys.all { it == state.value.selfId || it == id }) {
                    "This direct conversation belongs to another peer. The creator must stop hosting and make a new connection."
                }
                check(clients.size < (if (state.value.group) LanRules.MAX_MEMBERS - 1 else 1)) { "This conversation is full." }
                secret
            }
            if (welcome.group) {
                check(expected != null && withContext(Dispatchers.Default) { expected.matches(join.password) }) { "Incorrect group password." }
            } else {
                val consent = lock.withLock {
                    if (id in members) null else CompletableDeferred<Boolean>().also { pending ->
                        approvals[id] = pending
                        mutable.update { it.copy(requests = it.requests.filterNot { old -> old.id == id } + person) }
                    }
                }
                if (consent != null) {
                    try { check(withTimeout(120000) { consent.await() }) { "The other phone declined the connection." } }
                    finally { lock.withLock { approvals.remove(id); mutable.update { it.copy(requests = it.requests.filterNot { old -> old.id == id }) } } }
                }
            }
            socket.soTimeout = 20000
            connection = Connection(socket, id)
            lock.withLock {
                check(currentEpoch == epoch && state.value.hosting)
                check(clients.size < (if (state.value.group) LanRules.MAX_MEMBERS - 1 else 1) && id !in clients) { "This conversation is full." }
                check(state.value.group || members.keys.all { it == state.value.selfId || it == id }) { "This direct conversation belongs to another peer." }
                check(members.size < 256 || id in members) { "Create a new group to admit more distinct members." }
                clients[id] = checkNotNull(connection)
                members[id] = person
                admitted = true
                roster()
                store.messages.value.filter { it.roomId == state.value.roomId && id in LanRules.ids(it.targets) && it.delivery == "FAILED" }
                    .forEach { row -> store.mutate(row.key) { it.attempts = 0; it.nextAttemptMs = 0; it.delivery = "QUEUED" } }
            }
            while (currentCoroutineContext().isActive && currentEpoch == epoch) {
                val incomingBytes = Wire.read(socket.inputStream)
                val receiptAtMs = SystemClock.elapsedRealtime()
                val incoming = LanWire.decode(incomingBytes)
                lock.withLock {
                    check(currentEpoch == epoch && clients[id] === connection && incoming.roomId == state.value.roomId)
                    when (incoming.kind) {
                        LanKind.DATA -> route(incoming, id, incomingBytes.size + 4, receiptAtMs)
                        LanKind.RECEIPT -> receipt(incoming, id)
                        LanKind.HEARTBEAT -> Unit
                        else -> error("Unexpected member packet")
                    }
                }
            }
        } catch (error: Exception) {
            if (!admitted && error !is CancellationException) {
                diagnostics.put("lan_last_admission_error", error.javaClass.simpleName + ": " + error.message)
                android.util.Log.w("iTantraLAN", "Local TLS/admission failed", error)
                lastAdmission[ip] = SystemClock.elapsedRealtime() + 2000
                if (lastAdmission.size > 128) lastAdmission.entries.removeIf { it.value < SystemClock.elapsedRealtime() }
                runCatching { Wire.write(socket.outputStream, LanWire.encode(packet(LanKind.FAILURE).toBuilder()
                    .setError((error.message ?: "Connection rejected").take(512)).build())) }
            }
        } finally {
            connection?.close()
            withContext(NonCancellable) { lock.withLock {
                val id = connection?.id
                if (currentEpoch == epoch && id != null && clients[id] === connection) {
                    clients.remove(id)
                    members[id]?.let { members[id] = it.toBuilder().setOnline(false).build() }
                    runCatching { roster() }
                }
            } }
        }
    }

    private class JoinDenied(message: String) : IllegalStateException(message)
    suspend fun join(room: NearbyRoom, label: String) = lock.withLock {
        withContext(Dispatchers.IO) { discovery.networks.matching(room.address); tls.id }
        shutdown(); transportLabel = label
        val currentEpoch = epoch
        mutable.value = LanRoomState(active = true, group = room.group, roomId = room.id, name = room.name,
            selfId = tls.id, status = "Connecting to " + room.name)
        clientJob = scope.launch(Dispatchers.IO) {
            var trustedHost: String? = null
            var passwordForReconnect: String? = null
            var boundRoom = room.id
            var failures = 0
            while (isActive && currentEpoch == epoch && failures < 6) {
                var connection: Connection? = null
                var socket: SSLSocket? = null
                try {
                    val raw = discovery.networks.connect(room.address, room.port)
                    socket = try { tls.context(trustedHost).socketFactory.createSocket(raw, room.address, room.port, true) as SSLSocket }
                        catch (error: Exception) { raw.close(); throw error }
                    pendingSockets += socket
                    LanTls.configure(socket); socket.useClientMode = true; socket.startHandshake()
                    val fingerprint = LanTls.peerId(socket)
                    val welcome = LanWire.decode(Wire.read(socket.inputStream))
                    if (welcome.kind == LanKind.FAILURE) throw JoinDenied(welcome.error)
                    require(welcome.kind == LanKind.WELCOME && LanRules.room(welcome.roomId) && welcome.hostId == fingerprint)
                    require(boundRoom.isBlank() || welcome.roomId == boundRoom) { "That address now hosts a different group. Select it again." }
                    boundRoom = welcome.roomId
                    val name = LanRules.name(welcome.roomName)
                    if (trustedHost == null) {
                        val consent = CompletableDeferred<String?>()
                        lock.withLock {
                            check(currentEpoch == epoch)
                            confirmation = consent
                            mutable.update { it.copy(name = name, group = welcome.group, roomId = boundRoom, hostId = fingerprint,
                                confirmCode = LanRules.code(fingerprint), status = "Compare this code with the creator's phone") }
                        }
                        passwordForReconnect = withTimeout(120000) { consent.await() } ?: throw JoinDenied("Connection cancelled")
                        trustedHost = fingerprint // Set only AFTER the explicit code-confirmation action.
                        lock.withLock { confirmation = null; mutable.update { it.copy(confirmCode = "", status = "Joining; waiting for approval") } }
                    }
                    val request = LanPacket.newBuilder().setKind(LanKind.JOIN).setRoomId(boundRoom).setSenderId(tls.id)
                        .setSenderName(LanRules.name(displayName())).setPassword(if (welcome.group) passwordForReconnect.orEmpty() else "").build()
                    Wire.write(socket.outputStream, LanWire.encode(request))
                    socket.soTimeout = 120000
                    val roster = LanWire.decode(Wire.read(socket.inputStream))
                    if (roster.kind == LanKind.FAILURE) throw JoinDenied(roster.error)
                    require(roster.kind == LanKind.ROSTER && roster.roomId == boundRoom && roster.hostId == fingerprint)
                    socket.soTimeout = 20000
                    connection = Connection(socket, fingerprint)
                    lock.withLock {
                        check(currentEpoch == epoch)
                        upstream = connection
                        applyRoster(roster)
                        mutable.update { it.copy(connected = true, status = "Connected", error = null) }
                    }
                    failures = 0
                    while (isActive && currentEpoch == epoch) {
                        val incomingBytes = Wire.read(socket.inputStream)
                        val receiptAtMs = SystemClock.elapsedRealtime()
                        val incoming = LanWire.decode(incomingBytes)
                        lock.withLock {
                            check(epoch == currentEpoch && incoming.roomId == boundRoom && upstream === connection)
                            when (incoming.kind) {
                                LanKind.ROSTER -> applyRoster(incoming)
                                LanKind.DATA -> {
                                    LanRules.validateData(incoming, boundRoom, incoming.senderId, members.keys)
                                    require(incoming.hostId == fingerprint && tls.id in incoming.targetsList && incoming.senderId != tls.id)
                                    receiveLocal(incoming, incomingBytes.size + 4, receiptAtMs)
                                }
                                LanKind.RECEIPT -> applyReceipt(incoming)
                                LanKind.HEARTBEAT -> Unit
                                LanKind.FAILURE -> throw JoinDenied(incoming.error)
                                else -> error("Unexpected host packet")
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) break
                    failures++
                    lock.withLock {
                        if (epoch == currentEpoch) mutable.update { it.copy(connected = false, confirmCode = "",
                            error = error.message, status = if (error is JoinDenied) "Could not join" else "Host unavailable · reconnecting") }
                    }
                    if (trustedHost == null || error is JoinDenied) break
                } finally {
                    connection?.close(); socket?.let { pendingSockets -= it; runCatching { it.close() } }
                    withContext(NonCancellable) { lock.withLock {
                        if (currentEpoch == epoch && upstream === connection) upstream = null
                    } }
                }
                delay(minOf(failures * 1000L, 5000L))
            }
            lock.withLock {
                if (currentEpoch == epoch) { confirmation = null; mutable.update { it.copy(connected = false, active = false, confirmCode = "", status = "Disconnected · select the group to rejoin") } }
            }
        }
    }
    private suspend fun applyRoster(packet: LanPacket) {
        require(packet.hostId == state.value.hostId && packet.membersCount in 2..256)
        require(packet.membersList.count { it.online } <= LanRules.MAX_MEMBERS)
        require(packet.membersList.map { it.id }.distinct().size == packet.membersCount)
        require(packet.membersList.any { it.id == state.value.selfId && it.online } && packet.membersList.any { it.id == packet.hostId && it.online })
        packet.membersList.forEach { require(LanRules.identity(it.id)); LanRules.name(it.name) }
        val previouslyOnline = if (state.value.connected) members.values.filter { it.online }.map { it.id }.toSet() else emptySet()
        val returned = packet.membersList.filter { it.online && it.id != state.value.selfId && it.id !in previouslyOnline }.map { it.id }.toSet()
        members.clear(); packet.membersList.forEach { members[it.id] = it }
        mutable.update { it.copy(members = members.values.toList(), accepting = packet.accepting) }
        store.messages.value.filter { it.roomId == packet.roomId && it.direction == "OUT" && it.delivery == "FAILED" &&
            (LanRules.ids(it.targets) - LanRules.ids(it.deliveredTo)).any { id -> id in returned } }.forEach { row ->
            val retried = decode(row.lanPayload).toBuilder().setRetryId(randomId()).build()
            store.mutate(row.key) { it.attempts = 0; it.nextAttemptMs = 0; it.delivery = "QUEUED"; it.lanPayload = encode(retried) }
        }
    }
    suspend fun disconnect() = lock.withLock { shutdown() }
    private suspend fun shutdown() {
        epoch++
        clientJob?.cancel(); clientJob = null
        confirmation?.cancel(); confirmation = null
        approvals.values.forEach { it.cancel() }; approvals.clear()
        upstream?.close(); upstream = null
        clients.values.forEach { it.close() }; clients.clear()
        pendingSockets.toList().forEach { runCatching { it.close() } }; pendingSockets.clear()
        servers.forEach { runCatching { it.close() } }; servers.clear()
        serverJobs.forEach { it.cancel() }; serverJobs.clear()
        secret = null; members.clear(); hostedAddresses = emptySet()
        discovery.advertise(null)
        mutable.value = LanRoomState()
    }

    private fun messageKey(room: String, sender: String, id: Long, direction: String) = "$room/$sender/$id/$direction"
    private fun entity(packet: LanPacket, direction: String): MessageEntity {
        val message = Wire.decode(packet.envelope.toByteArray())
        val chat = !message.body.isEmpty && TextOptionsBody.parseFrom(message.body).silentChat
        return MessageEntity().apply {
            key = messageKey(packet.roomId, packet.senderId, message.messageId, direction)
            roomId = packet.roomId; roomName = packet.roomName; senderId = packet.senderId; targetId = packet.recipientId
            targets = LanRules.joinIds(packet.targetsList); lanPayload = encode(packet)
            peerId = if (direction == "OUT") packet.recipientId.ifBlank { roomId } else packet.senderId
            peerName = if (direction == "OUT") members[packet.recipientId]?.name ?: roomName else packet.senderName
            messageId = message.messageId; sessionId = message.sessionId
            sequence = if (packet.roomOrder in 1..Int.MAX_VALUE.toLong()) packet.roomOrder.toInt() else message.sequence
            this.direction = direction; text = message.text; language = Wire.language(message.language).code
            emergency = message.type == MessageType.ALERT; createdAtMs = System.currentTimeMillis()
            receiptElapsedMs = SystemClock.elapsedRealtime(); transport = transportLabel
            channel = if (direction == "RELAY") "RELAY" else if (chat) "CHAT" else "VOICE"
            playback = if (channel == "VOICE" && direction == "IN") "PENDING" else "NOT_APPLICABLE"
            delivery = if (direction == "IN") "DELIVERED" else "QUEUED"
            wireBytes = LanWire.encode(packet).size + 4
        }
    }
    suspend fun enqueue(text: String, language: LanguageCode, emergency: Boolean, speechEnd: Long = 0,
        chat: Boolean = false, recipient: String = "", measurementId: Long? = null,
        mayEnqueue: () -> Boolean = { true }) = lock.withLock {
        val status = state.value
        check(status.ready) { "Connect to a person or wait for someone to join your group." }
        require(recipient.isBlank() || chat) { "Direct messages are text-only. Talk stays in the group." }
        require(!chat || !emergency)
        val targets = LanRules.audience(status.selfId, status.members, recipient)
        val parts = UnicodeText.chunks(text)
        require(parts.isNotEmpty() && parts.size <= 16)
        check(store.messages.value.count { it.roomId == status.roomId && it.direction == "OUT" && it.delivery in listOf("QUEUED", "SENDING", "FAILED", "PARTIAL") } + parts.size <= 256) {
            "Too many pending messages. Reconnect and retry, or clear history explicitly."
        }
        val keys = mutableListOf<String>()
        for (part in parts) {
            val envelope = Envelope.newBuilder().setProtocolMajor(1).setProtocolMinor(1).setSessionId(localSession)
                .setMessageId(randomId()).setSequence(store.nextSequence()).setLanguage(Wire.language(language))
                .setText(part).setType(if (emergency) MessageType.ALERT else MessageType.TEXT_FINAL)
                .setSentElapsedMs(SystemClock.elapsedRealtime())
            if (chat) envelope.body = TextOptionsBody.newBuilder().setSilentChat(true).build().toByteString()
            val data = packet(LanKind.DATA).toBuilder().setSenderId(status.selfId).setSenderName(LanRules.name(displayName()))
                .setRecipientId(recipient).addAllTargets(targets).setEnvelope(ByteString.copyFrom(Wire.encode(envelope))).build()
            val row = entity(data, "OUT").apply { speechEndElapsedMs = speechEnd }
            check(store.insert(row, mayEnqueue))
            diagnostics.bind(measurementId, row)
            keys += row.key
        }
        // Persistence is the enqueue boundary. A socket failure afterwards must not
        // invite a second SOS with new IDs; the existing pump retries these same rows.
        try { pump() } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(error = error.message ?: "Delivery paused; alert remains queued") }
        }
        keys.toList()
    }
    private suspend fun route(incoming: LanPacket, authenticated: String, receivedBytes: Int? = null,
        receiptAtMs: Long = SystemClock.elapsedRealtime()) {
        LanRules.validateData(incoming, state.value.roomId, authenticated, members.keys)
        val envelope = Wire.decode(incoming.envelope.toByteArray())
        val key = messageKey(incoming.roomId, incoming.senderId, envelope.messageId, "RELAY")
        var existing = store.find(key)
        val canonical: LanPacket
        if (existing == null) {
            check(store.messages.value.count { it.roomId == state.value.roomId && it.direction == "RELAY" && !allDelivered(it) } < 256) { "Group delivery queue is full." }
            order = Math.addExact(order, 1L)
            canonical = incoming.toBuilder().setHostId(state.value.hostId).setRoomName(state.value.name).setGroup(state.value.group)
                .setSenderName(checkNotNull(members[authenticated]).name).setRoomOrder(order).clearMembers().clearPassword().clearError().build()
            store.insert(entity(canonical, "RELAY"))
            existing = store.find(key)!!
        } else {
            val original = decode(existing.lanPayload)
            require(LanRules.sameMessage(original, incoming)) { "A retry changed the message or its original recipients." }
            canonical = if (incoming.retryId != 0L && original.retryId != incoming.retryId) {
                val next = original.toBuilder().setRetryId(incoming.retryId).build()
                store.mutate(key) { it.attempts = 0; it.nextAttemptMs = 0; it.delivery = "QUEUED"; it.lanPayload = encode(next) }
                next
            } else original
        }
        if (state.value.selfId in canonical.targetsList) receiveLocal(canonical,
            checkNotNull(receivedBytes), receiptAtMs)
        val current = store.find(key)!!
        if (!allDelivered(current) && current.attempts < DeliveryPolicy.MAX_SENDS && current.nextAttemptMs <= SystemClock.elapsedRealtime()) sendRelay(current)
        replayReceipts(store.find(key)!!)
    }
    private suspend fun receiveLocal(packet: LanPacket, receivedBytes: Int, receiptAtMs: Long) {
        require(state.value.selfId in packet.targetsList && packet.senderId != state.value.selfId)
        val row = entity(packet, "IN").apply { wireBytes = receivedBytes; receiptElapsedMs = receiptAtMs }
        val inserted = store.insert(row)
        val saved = store.find(row.key)!!
        require(LanRules.sameMessage(decode(saved.lanPayload), packet)) { "Duplicate message contents changed" }
        sendReceipt(saved, MessageType.ACK_DELIVERED)
        if (saved.playback == "PLAYED") sendReceipt(saved, MessageType.ACK_PLAYED)
        if (saved.humanAcknowledged) sendReceipt(saved, MessageType.ACKNOWLEDGED)
        if (inserted && saved.channel == "VOICE") received(saved)
    }
    private fun allDelivered(row: MessageEntity) = LanRules.ids(row.targets).let { it.isNotEmpty() && LanRules.ids(row.deliveredTo).containsAll(it) }
    private suspend fun pump() {
        if (!state.value.connected) return
        val now = SystemClock.elapsedRealtime()
        val outgoing = store.messages.value.filter { it.roomId == state.value.roomId && it.direction == "OUT" && !allDelivered(it) }
            .sortedWith(compareByDescending<MessageEntity> { it.emergency }.thenBy { it.createdAtMs }.thenBy { it.sequence })
        for (row in outgoing) {
            if (row.nextAttemptMs > now || row.delivery == "FAILED") continue
            if (row.attempts >= DeliveryPolicy.MAX_SENDS) { store.mutate(row.key) { it.delivery = "FAILED" }; continue }
            if (!state.value.ready) continue
            val data = decode(row.lanPayload)
            store.mutate(row.key) {
                it.attempts++; it.nextAttemptMs = now + DeliveryPolicy.waitAfterAttempt(it.attempts)
                if (it.firstSendElapsedMs == 0L) it.firstSendElapsedMs = now
                it.delivery = if (LanRules.ids(it.deliveredTo).isEmpty()) "SENDING" else "PARTIAL"
            }
            if (state.value.hosting) route(data, state.value.selfId) else checkNotNull(upstream).send(data)
        }
        if (state.value.hosting) {
            store.messages.value.filter { it.roomId == state.value.roomId && it.direction == "RELAY" && !allDelivered(it) &&
                it.nextAttemptMs <= now && it.delivery != "FAILED" }.sortedWith(compareByDescending<MessageEntity> { it.emergency }.thenBy { it.createdAtMs })
                .forEach { row ->
                    if (row.attempts >= DeliveryPolicy.MAX_SENDS) store.mutate(row.key) { it.delivery = "FAILED" }
                    else sendRelay(row)
                }
        }
    }
    private suspend fun sendRelay(row: MessageEntity) {
        val delivered = LanRules.ids(row.deliveredTo)
        val pending = LanRules.ids(row.targets).filter { it !in delivered && it in clients }
        if (pending.isEmpty()) return // Offline recipients retain their original outbox entries.
        val data = decode(row.lanPayload)
        store.mutate(row.key) { it.attempts++; it.nextAttemptMs = SystemClock.elapsedRealtime() + DeliveryPolicy.waitAfterAttempt(it.attempts); it.delivery = "SENDING" }
        pending.forEach { id -> runCatching { clients[id]?.send(data) } }
    }
    private suspend fun sendReceipt(row: MessageEntity, type: MessageType, receiptMs: Long = -1, durationMs: Long = 0) {
        if (!state.value.connected || row.roomId != state.value.roomId) return
        val envelope = Envelope.newBuilder().setProtocolMajor(1).setProtocolMinor(1).setSessionId(localSession)
            .setMessageId(row.messageId).setType(type)
        if (type == MessageType.ACK_PLAYED && receiptMs >= 0) envelope.body = org.itantra.protocol.v1.AckBody.newBuilder()
            .setReceiptToPlaybackSubmissionMs(receiptMs).setPlaybackDurationMs(durationMs).build().toByteString()
        val response = packet(LanKind.RECEIPT).toBuilder().setSenderId(row.senderId).setRecipientId(state.value.selfId)
            .setEnvelope(ByteString.copyFrom(Wire.encode(envelope))).build()
        if (state.value.hosting) receipt(response, state.value.selfId) else upstream?.send(response)
    }
    private suspend fun receipt(packet: LanPacket, authenticated: String) {
        require(packet.kind == LanKind.RECEIPT && packet.roomId == state.value.roomId && packet.recipientId == authenticated)
        val ack = Wire.decode(packet.envelope.toByteArray())
        require(ack.type in listOf(MessageType.ACK_DELIVERED, MessageType.ACK_PLAYED, MessageType.ACKNOWLEDGED))
        val original = store.find(messageKey(packet.roomId, packet.senderId, ack.messageId, "RELAY")) ?: return
        require(authenticated in LanRules.ids(original.targets)) { "Receipt is not from an intended recipient" }
        updateReceipt(original.key, authenticated, ack.type)
        if (packet.senderId == state.value.selfId) applyReceipt(packet)
        else clients[packet.senderId]?.let { runCatching { it.send(packet.toBuilder().setHostId(state.value.hostId).build()) } }
    }
    private suspend fun applyReceipt(packet: LanPacket) {
        require(packet.roomId == state.value.roomId && packet.hostId == state.value.hostId && packet.senderId == state.value.selfId)
        val ack = Wire.decode(packet.envelope.toByteArray())
        require(ack.type in listOf(MessageType.ACK_DELIVERED, MessageType.ACK_PLAYED, MessageType.ACKNOWLEDGED))
        val row = store.find(messageKey(packet.roomId, packet.senderId, ack.messageId, "OUT")) ?: return
        require(packet.recipientId in LanRules.ids(row.targets))
        updateReceipt(row.key, packet.recipientId, ack.type)
        diagnostics.acknowledge(row.key, packet.recipientId, ack.type == MessageType.ACK_PLAYED, SystemClock.elapsedRealtimeNanos())
    }
    private suspend fun updateReceipt(key: String, recipient: String, type: MessageType) {
        store.mutate(key) { row ->
            row.deliveredTo = LanRules.joinIds(LanRules.ids(row.deliveredTo) + recipient)
            if (type == MessageType.ACK_PLAYED) row.playedBy = LanRules.joinIds(LanRules.ids(row.playedBy) + recipient)
            if (type == MessageType.ACKNOWLEDGED) row.acknowledgedBy = LanRules.joinIds(LanRules.ids(row.acknowledgedBy) + recipient)
            val targets = LanRules.ids(row.targets)
            row.delivery = when {
                LanRules.ids(row.acknowledgedBy).containsAll(targets) -> "ACKNOWLEDGED"
                LanRules.ids(row.playedBy).containsAll(targets) -> "PLAYED"
                LanRules.ids(row.deliveredTo).containsAll(targets) -> "DELIVERED"
                else -> "PARTIAL"
            }
        }
    }
    private suspend fun replayReceipts(row: MessageEntity) {
        val origin = clients[row.senderId] ?: return
        for ((ids, type) in listOf(row.deliveredTo to MessageType.ACK_DELIVERED, row.playedBy to MessageType.ACK_PLAYED, row.acknowledgedBy to MessageType.ACKNOWLEDGED)) {
            LanRules.ids(ids).forEach { id ->
                val ack = Envelope.newBuilder().setProtocolMajor(1).setProtocolMinor(1).setSessionId(localSession).setMessageId(row.messageId).setType(type)
                runCatching { origin.send(packet(LanKind.RECEIPT).toBuilder().setSenderId(row.senderId).setRecipientId(id)
                    .setEnvelope(ByteString.copyFrom(Wire.encode(ack))).build()) }
            }
        }
    }
    suspend fun played(message: MessageEntity, receiptMs: Long, durationMs: Long) = lock.withLock {
        store.mutate(message.key) { it.playback = "PLAYED" }
        sendReceipt(message, MessageType.ACK_PLAYED, receiptMs, durationMs)
    }
    suspend fun acknowledge(key: String) = lock.withLock {
        val row = store.mutate(key) { it.humanAcknowledged = true } ?: return@withLock
        if (row.direction == "IN") sendReceipt(row, MessageType.ACKNOWLEDGED)
    }
    suspend fun retry(key: String) = lock.withLock {
        val row = store.find(key) ?: return@withLock
        check(row.roomId == state.value.roomId && state.value.ready && row.direction == "OUT") { "Rejoin the original conversation to retry." }
        val data = decode(row.lanPayload).toBuilder().setRetryId(randomId()).build()
        store.mutate(key) { it.attempts = 0; it.nextAttemptMs = 0; it.delivery = "QUEUED"; it.lanPayload = encode(data) }
        pump()
    }
    suspend fun markRead(keys: List<String>) { keys.forEach { key -> store.mutate(key) { it.readLocally = true } } }
    suspend fun recoverOutbox() = lock.withLock {
        store.refresh()
        store.messages.value.filter { it.roomId.isNotBlank() && !it.roomId.startsWith("ble:") }.forEach { row ->
            store.mutate(row.key) {
                it.nextAttemptMs = 0; it.firstSendElapsedMs = 0; it.speechEndElapsedMs = 0; it.receiptElapsedMs = 0
                it.playback = PlaybackQueue.recoveredStatus(it.playback)
                if (it.delivery in listOf("QUEUED", "SENDING", "PARTIAL")) it.attempts = 0
            }
        }
    }
    private fun encode(packet: LanPacket) = Base64.encodeToString(LanWire.encode(packet), Base64.NO_WRAP)
    private fun decode(value: String) = LanWire.decode(Base64.decode(value, Base64.NO_WRAP))
}
