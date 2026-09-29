package org.itantra.app.relay

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.itantra.app.core.*
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import java.util.UUID

data class PublicRelayState(val active: Boolean = false, val starting: Boolean = false, val epoch: Int = 0,
    val nearby: Int = 0, val queued: Int = 0, val forwarded: Int = 0,
    val detail: String = "Off. Turn on to receive and automatically forward public SOS alerts.")

/** Public SOS opt-in is independent of conversations and private team keys. Never auto-starts.
 * In-memory forwarding ends on stop/process death; Room retains history and SOS deduplication. */
class PublicRelaySession(private val context: Context, private val scope: CoroutineScope,
    private val store: MessageStore, private val diagnostics: Diagnostics,
    private val foregroundReady: suspend () -> Unit, private val incoming: (MessageEntity) -> Unit) {
    companion object {
        const val TRANSPORT = "Public BLE SOS (experimental)"
        const val ROOM = "ble:public"
        const val PREFIX = "ble:public:"
        fun owns(row: MessageEntity) = row.transport == TRANSPORT
        val SERVICE: UUID = UUID.fromString("6c63d6b8-657d-4ac0-9e7e-6cc66479b401")
        val MAILBOX: UUID = UUID.fromString("6c63d6b8-657d-4ac0-9e7e-6cc66479b402")
    }
    private val mutable = MutableStateFlow(PublicRelayState())
    val state = mutable.asStateFlow()
    private val identity = RelayIdentity(context, "public-relay")
    private val signingWorker = Dispatchers.IO.limitedParallelism(1)
    private val ledger = PublicRelayLedger()
    private val acceptedIncoming = mutableSetOf<String>()
    val playableKeys: Set<String> get() = acceptedIncoming.toSet()
    private val peers = linkedMapOf<String, Pair<BluetoothDevice, Long>>()
    private data class Received(val frame: ByteArray, val count: Int, val epoch: Int)
    private val received = Channel<Received>(16)
    private val ready = CompletableDeferred<Unit>()
    private var operation: Job? = null
    private var verificationWindow = 0L
    private var verifications = 0
    private val link = BleRelayLink(context, scope, { bytes, count ->
        if (state.value.active && !state.value.starting) received.trySend(Received(bytes, count, state.value.epoch))
    }, { address, device ->
        if (state.value.active) {
            if (peers.size >= 32 && address !in peers) peers.remove(peers.keys.first())
            peers[address] = device to now(); update()
        }
    }, { message -> stop(message) }, SERVICE, MAILBOX)

    init {
        scope.launch {
            try {
                store.refresh()
                for (row in store.messages.value.filter(::owns)) store.mutate(row.key) {
                    if (it.direction == "OUT" && it.delivery in listOf("QUEUED", "RELAYED")) it.delivery = "EXPIRED"
                    if (it.playback in listOf("PENDING", "PLAYING", "WAITING_AUDIO")) it.playback = "INTERRUPTED"
                }
                ready.complete(Unit)
            } catch (error: Exception) { ready.completeExceptionally(error) }
        }
        scope.launch {
            for (item in received) {
                if (!current(item.epoch)) continue
                if (now() - verificationWindow >= 60_000) { verificationWindow = now(); verifications = 0 }
                if (++verifications > 180) continue // Bound unauthenticated signature work as well as queue size.
                try { receive(item) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Malformed, expired, duplicate or over-limit public traffic is never spoken.
                }
            }
        }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun current(epoch: Int) = state.value.active && !state.value.starting && state.value.epoch == epoch
    fun audienceKey() = if (current(state.value.epoch)) PREFIX + state.value.epoch else ""
    fun start() {
        if (state.value.active || state.value.starting) return
        val epoch = state.value.epoch + 1
        mutable.value = PublicRelayState(active = true, starting = true, epoch = epoch, detail = "Starting public BLE SOS…")
        operation = scope.launch {
            try {
                ready.await()
                ensureActive()
                check(state.value.active && state.value.epoch == epoch)
                foregroundReady() // Await Android foreground promotion before starting the radio.
                withContext(signingWorker) { identity.publicKey }
                link.start("7075626c69633031") // Public protocol tag, NOT a password or encryption key.
                ensureActive()
                check(state.value.epoch == epoch)
                mutable.update { it.copy(starting = false, detail = "Receiving and automatic forwarding on · stops after 30 minutes.") }
                val until = now() + 30 * 60_000
                while (isActive && current(epoch) && now() < until) {
                    for (expired in ledger.expire(now())) if (expired.hops == 0 && expired.signed.message.kind == RelayKind.SOS)
                        store.mutate(PREFIX + expired.signed.key) { if (it.delivery in listOf("QUEUED", "RELAYED")) it.delivery = "EXPIRED" }
                    peers.entries.removeAll { now() - it.value.second > 90_000 }; update()
                    for ((address, peer) in peers.toMap().entries.shuffled()) {
                        if (!current(epoch)) break
                        val frames = ledger.candidates(address, now(), System.currentTimeMillis()).mapNotNull { entry ->
                            val remaining = minOf(entry.deadline - now(), entry.signed.expiresAtMs - System.currentTimeMillis()) - 30_000
                            if (remaining <= 0) null else entry.signed.key to PublicRelayPacket.encode(PublicRelayPacket(entry.signed, entry.hops + 1, remaining))
                        }
                        if (frames.isEmpty()) continue
                        frames.forEach { ledger.attempting(it.first, address, now()) }
                        try {
                            withTimeout(30_000) { link.send(peer.first, frames) { key, bytes ->
                                if (current(epoch)) {
                                    ledger.sent(key, address)
                                    val entry = ledger.pending[key]
                                    if (entry?.hops == 0 && entry.signed.message.kind == RelayKind.SOS) scope.launch {
                                        store.mutate(PREFIX + key) { if (it.delivery == "QUEUED") it.delivery = "RELAYED"; it.wireBytes += bytes }
                                        diagnostics.transmitted(PREFIX + key, bytes, TRANSPORT)
                                    }
                                    mutable.update { it.copy(forwarded = it.forwarded + 1) }
                                }
                            } }
                        } catch (error: Exception) { currentCoroutineContext().ensureActive() }
                    }
                    // Small randomized delay avoids neighboring phones repeatedly connecting in lockstep.
                    delay(kotlin.random.Random.nextLong(1500, 3001))
                }
                if (state.value.epoch == epoch) stop("30-minute receiving session ended. Turn on again when needed.")
            } catch (error: Exception) {
                if (state.value.epoch == epoch) stop(error.message ?: "BLE SOS stopped. Check Bluetooth and permissions.")
            }
        }
    }
    fun stop(detail: String = "Receiving and forwarding off. History is kept; pending public transmissions are cancelled.") {
        operation?.cancel(); operation = null; link.stop(); peers.clear(); ledger.stop(); acceptedIncoming.clear()
        mutable.update { it.copy(active = false, starting = false, epoch = it.epoch + 1, nearby = 0, queued = 0, detail = detail) }
        while (received.tryReceive().isSuccess) { /* discard old-session frames */ }
        val oldRows = store.messages.value.filter(::owns).map { it.key }
        scope.launch { for (key in oldRows) store.mutate(key) {
            if (it.direction == "OUT" && it.delivery in listOf("QUEUED", "RELAYED")) it.delivery = "CANCELLED"
            if (it.playback in listOf("PENDING", "WAITING_AUDIO")) it.playback = "INTERRUPTED"
        } }
    }
    suspend fun enqueue(text: String, language: LanguageCode, measurementId: Long?): String {
        val epoch = state.value.epoch
        check(current(epoch)) { "Turn on receiving and automatic relay before sending." }
        val signed = create(RelayKind.SOS, language.code, UnicodeText.normalize(text))
        check(current(epoch)) { "BLE SOS stopped. Review and confirm again." }
        check(ledger.accept(signed, 0, RelayPacket.TTL_MS, now(), System.currentTimeMillis())) {
            "Public SOS limit reached. Wait a minute before sending another alert, or wait for queue expiry."
        }
        // Do not expose a packet to the pump before its local history row is committed.
        val pending = checkNotNull(ledger.pending.remove(signed.key))
        val row = entity(signed, "OUT", 0, 0)
        check(store.insert(row) { current(epoch) })
        if (!current(epoch)) {
            store.mutate(row.key) { it.delivery = "CANCELLED" }
            error("BLE SOS stopped before transmission. Alert kept in history.")
        }
        ledger.pending[signed.key] = pending
        diagnostics.bind(measurementId, row, unknownRecipients = true); update()
        return row.key
    }
    private suspend fun receive(item: Received) {
        val packet = withContext(Dispatchers.Default) { PublicRelayPacket.decode(item.frame, System.currentTimeMillis()) }
        if (!current(item.epoch)) return
        val signed = packet.signed; val message = signed.message
        if (message.kind == RelayKind.SOS) {
            // Room provides durable duplicate suppression across opt-out and process restart.
            if (store.find(PREFIX + signed.key) != null || !current(item.epoch)) return
        } else {
            val original = ledger.pending[message.recipient + ":" + message.reference] ?: return
            if (original.signed.message.kind != RelayKind.SOS || original.signed.expiresAtMs <= System.currentTimeMillis()) return
        }
        if (!ledger.accept(signed, packet.hops, packet.remainingMs, now(), System.currentTimeMillis())) return
        if (message.kind == RelayKind.SOS) {
            val pending = checkNotNull(ledger.pending.remove(signed.key))
            val row = entity(signed, "IN", item.count, packet.hops)
            if (!store.insert(row) { current(item.epoch) }) return
            if (!current(item.epoch)) { store.mutate(row.key) { it.playback = "INTERRUPTED" }; return }
            ledger.pending[signed.key] = pending // Automatic forwarding, no UI acknowledgement required.
            acceptedIncoming += row.key
            diagnostics.received(row); incoming(row)
            receipt(signed, RelayKind.DELIVERED)
        } else {
            val key = PREFIX + message.recipient + ":" + message.reference
            val row = store.find(key)
            if (current(item.epoch) && row != null && owns(row) && row.direction == "OUT") {
                store.mutate(key) {
                    it.deliveredTo = ids(it.deliveredTo, message.sender)
                    if (message.kind == RelayKind.PLAYED) it.playedBy = ids(it.playedBy, message.sender)
                    if (message.kind == RelayKind.ACKNOWLEDGED) it.acknowledgedBy = ids(it.acknowledgedBy, message.sender)
                    it.delivery = if (it.acknowledgedBy.isNotBlank()) "ACKNOWLEDGED" else if (it.playedBy.isNotBlank()) "PLAYED" else "DELIVERED"
                }
                diagnostics.acknowledge(key, message.sender, message.kind == RelayKind.PLAYED, SystemClock.elapsedRealtimeNanos())
            }
        }
        update()
    }
    private suspend fun create(kind: RelayKind, language: String, text: String, original: RelayMessage? = null) = withContext(signingWorker) {
        PublicRelayPacket.create(kind, language, text, identity.publicKey, identity.messageId(), System.currentTimeMillis(),
            original?.id ?: "", original?.sender ?: "", identity::sign)
    }
    private suspend fun receipt(original: PublicRelayMessage, kind: RelayKind) {
        val epoch = state.value.epoch
        if (!current(epoch) || original.expiresAtMs <= System.currentTimeMillis()) return
        val signed = create(kind, original.message.language, "", original.message)
        if (current(epoch)) ledger.accept(signed, 0, RelayPacket.TTL_MS, now(), System.currentTimeMillis())
        update()
    }
    suspend fun played(row: MessageEntity) {
        store.mutate(row.key) { it.playback = "PLAYED" }
        ledger.pending[row.key.removePrefix(PREFIX)]?.signed?.let { receipt(it, RelayKind.PLAYED) }
    }
    suspend fun acknowledge(row: MessageEntity) {
        store.mutate(row.key) { it.humanAcknowledged = true }
        ledger.pending[row.key.removePrefix(PREFIX)]?.signed?.let { receipt(it, RelayKind.ACKNOWLEDGED) }
    }
    private fun ids(value: String, id: String) = (value.split(',').filter { it.isNotBlank() }.toSet() + id).take(128).joinToString(",")
    private fun entity(signed: PublicRelayMessage, direction: String, bytes: Int, hops: Int) = MessageEntity().apply {
        val m = signed.message
        key = PREFIX + m.key; senderId = m.sender; peerId = m.sender; peerName = "Unverified public sender " + m.sender.take(4)
        messageId = m.id.take(16).toLong(16); text = m.text; language = m.language; emergency = true
        this.direction = direction; roomId = ROOM; roomName = "Public BLE SOS"; lanPayload = hops.toString()
        transport = TRANSPORT; channel = "VOICE"; createdAtMs = System.currentTimeMillis(); receiptElapsedMs = now()
        delivery = if (direction == "OUT") "QUEUED" else "RECEIVED"; wireBytes = bytes
        if (direction == "OUT") playback = "NONE"
    }
    private fun update() { mutable.update { it.copy(nearby = peers.size, queued = ledger.pending.size) } }
}
