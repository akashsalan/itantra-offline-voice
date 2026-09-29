package org.itantra.app.relay

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.app.core.*
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RelayState(val configured: Boolean = false, val active: Boolean = false, val starting: Boolean = false,
    val teamId: String = "", val nearby: Int = 0, val queued: Int = 0, val forwarded: Int = 0,
    val epoch: Int = 0, val detail: String = "Experimental · requires a shared trusted-team code")

/** Isolated emergency-only transport. Existing RadioSession and LanSession never pump these rows. */
class RelaySession(private val context: Context, private val scope: CoroutineScope, private val store: MessageStore,
    private val diagnostics: Diagnostics, private val incoming: (MessageEntity) -> Unit) {
    companion object { const val TRANSPORT = "BLE emergency relay (experimental)"; fun owns(row: MessageEntity) = row.transport == TRANSPORT }
    private val identity = RelayIdentity(context)
    private val mutable = MutableStateFlow(RelayState())
    val state = mutable.asStateFlow()
    private var key: ByteArray? = null
    private var ledger = RelayLedger()
    private val storage = AtomicFile(File(context.noBackupFilesDir, "relay-queue.json"))
    private val persistence = Mutex()
    private val received = Channel<Pair<ByteArray, Int>>(16)
    private val peers = linkedMapOf<String, Pair<BluetoothDevice, Long>>()
    private val link = BleRelayLink(context, scope, { bytes, count -> received.trySend(bytes to count) }, { id, device ->
        if (peers.size >= 32 && id !in peers) peers.remove(peers.keys.first())
        peers[id] = device to now(); update()
    }, { message -> mutable.update { it.copy(detail = message) } })
    private var operation: Job? = null
    private var pump: Job? = null
    private val ready = CompletableDeferred<Unit>()
    init {
        scope.launch {
            try {
                key = withContext(Dispatchers.IO) { identity.read() }
                key?.let { secret ->
                    mutable.value = RelayState(configured = true, teamId = RelayPacket.teamId(secret))
                    restore(secret)
                }
            } catch (_: Exception) { mutable.update { it.copy(detail = "Relay setup could not be recovered. Join the trusted team again.") } }
            finally { ready.complete(Unit) }
        }
        scope.launch { for ((frame, count) in received) if (state.value.active) runCatching { receive(frame, count) } }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun rowKey(message: RelayMessage) = "ble:" + message.key
    fun audienceKey() = if (state.value.active && !state.value.starting) "ble:${state.value.teamId}:${state.value.epoch}" else ""
    fun configure(code: String, done: (Boolean) -> Unit) {
        if (state.value.active || state.value.starting) { done(false); return }
        mutable.update { it.copy(starting = true) }
        scope.launch {
            ready.await()
            try {
                val secret = withContext(Dispatchers.IO) { identity.save(code) }
                if (key?.contentEquals(secret) != true) ledger = RelayLedger()
                key = secret; peers.clear()
                mutable.value = RelayState(configured = true, teamId = RelayPacket.teamId(secret), epoch = state.value.epoch + 1,
                    detail = "Team saved. Start relay on each trusted phone. No internet or Wi-Fi is required.")
                persist(); done(true)
            } catch (_: Exception) { mutable.update { it.copy(starting = false, detail = "Could not save team. Check the full 64-character code and retry.") }; done(false) }
        }
    }
    fun start() {
        if (state.value.active || state.value.starting) return
        mutable.update { it.copy(starting = true) }
        operation = scope.launch {
            ready.await()
            if (key == null) { mutable.update { it.copy(starting = false, detail = "Create or join a trusted team first.") }; return@launch }
            mutable.update { it.copy(active = true, starting = true, epoch = it.epoch + 1, detail = "Starting encrypted BLE relay…") }
            val epoch = state.value.epoch
            try {
                link.start(state.value.teamId)
                mutable.update { it.copy(starting = false, detail = "Relay active for up to 30 minutes. Keep Bluetooth on. No delivery guarantee.") }
                pump = scope.launch {
                    try {
                    val until = now() + 30 * 60_000
                    while (isActive && state.value.active && now() < until) {
                        expire()
                        peers.entries.removeAll { now() - it.value.second > 90000 }
                        update()
                        for ((address, peer) in peers.toMap()) {
                            val selected = ledger.candidates(address, now())
                            if (selected.isEmpty()) continue
                            val secret = key ?: break
                            // Subtract a full transfer timeout, so hop transit cannot extend the lifetime.
                            val frames = selected.mapNotNull { pending ->
                                val budget = pending.deadline - now() - 30000
                                if (budget <= 0) null else pending.message.key to RelayPacket.seal(
                                    RelayPacket(pending.message, pending.hops + 1, budget), secret)
                            }
                            frames.forEach { ledger.attempting(it.first, address, now()) }
                            runCatching { withTimeout(30000) {
                                link.send(peer.first, frames) { id, bytes -> scope.launch {
                                    ledger.pending[id]?.let { pending ->
                                        val row = rowKey(pending.message)
                                        if (pending.hops == 0 && pending.message.kind == RelayKind.SOS) {
                                            store.mutate(row) { if (it.delivery == "QUEUED") it.delivery = "RELAYED"; it.wireBytes += bytes }
                                            diagnostics.transmitted(row, bytes, TRANSPORT)
                                        }
                                        mutable.update { it.copy(forwarded = it.forwarded + 1) }
                                    }
                                } }
                            } }
                            if (!isActive) break
                        }
                        persist(); delay(3000)
                    }
                    if (state.value.active) stop()
                    } catch (error: Exception) {
                        if (error !is CancellationException && state.value.epoch == epoch) {
                            stop(); mutable.update { it.copy(detail = "Relay stopped after a local error. Existing history is kept. Restart relay to retry unexpired alerts.") }
                        }
                    }
                }
            } catch (error: Exception) {
                if (state.value.epoch == epoch) {
                    link.stop(); mutable.update { it.copy(active = false, starting = false,
                        detail = if (error is CancellationException) "Relay stopped." else error.message ?: "Could not start relay. Check Bluetooth and permissions.") }
                }
            }
        }
    }
    fun stop() {
        operation?.cancel(); pump?.cancel(); operation = null; pump = null; link.stop(); peers.clear()
        mutable.update { it.copy(active = false, starting = false, nearby = 0, epoch = it.epoch + 1,
            detail = "Relay stopped. Unexpired alerts can resume only when you start it again.") }
        scope.launch { persist() }
    }
    suspend fun enqueue(text: String, language: LanguageCode, measurementId: Long?): String {
        check(state.value.active && !state.value.starting) { "Start the BLE relay first." }
        val audience = audienceKey()
        val message = withContext(Dispatchers.IO) { RelayPacket.create(RelayKind.SOS, language.code, UnicodeText.normalize(text), identity.publicKey, id = identity.messageId(), sign = identity::sign) }
        check(audience.isNotBlank() && audience == audienceKey()) { "Relay team changed. Confirm the SOS again." }
        check(ledger.accept(message, 0, RelayPacket.TTL_MS, now()))
        val row = entity(message, "OUT", 0)
        check(store.insert(row)); diagnostics.bind(measurementId, row, unknownRecipients = true)
        persist(); update()
        return row.key
    }
    private suspend fun receive(frame: ByteArray, framedBytes: Int) {
        val secret = key ?: return
        val packet = withContext(Dispatchers.Default) { RelayPacket.open(frame, secret) }
        if (!state.value.active || secret !== key) return
        val message = packet.message
        if (!ledger.accept(message, packet.hops, packet.remainingMs, now())) return
        persist(); update()
        if (message.kind == RelayKind.SOS) {
            val row = entity(message, "IN", framedBytes)
            if (store.insert(row)) { diagnostics.received(row); incoming(row) }
            receipt(message, RelayKind.DELIVERED)
        } else {
            val originalKey = "ble:" + message.recipient + ":" + message.reference
            val original = store.find(originalKey) ?: return
            if (!owns(original) || original.direction != "OUT" || original.roomId != "ble:" + state.value.teamId) return
            store.mutate(originalKey) {
                it.deliveredTo = ids(it.deliveredTo, message.sender)
                if (message.kind == RelayKind.PLAYED) it.playedBy = ids(it.playedBy, message.sender)
                if (message.kind == RelayKind.ACKNOWLEDGED) it.acknowledgedBy = ids(it.acknowledgedBy, message.sender)
                it.delivery = if (it.acknowledgedBy.isNotBlank()) "ACKNOWLEDGED" else if (it.playedBy.isNotBlank()) "PLAYED" else "DELIVERED"
            }
            diagnostics.acknowledge(originalKey, message.sender, message.kind == RelayKind.PLAYED, SystemClock.elapsedRealtimeNanos())
        }
    }
    private fun ids(value: String, id: String) = (value.split(',').filter { it.isNotBlank() }.toSet() + id).take(128).joinToString(",")
    private fun entity(message: RelayMessage, direction: String, bytes: Int) = MessageEntity().apply {
        key = rowKey(message); senderId = message.sender; peerId = message.sender; peerName = "Trusted team member " + message.sender.take(4)
        messageId = message.id.take(16).toLong(16); text = message.text; language = message.language; emergency = true
        this.direction = direction; roomId = "ble:" + state.value.teamId; roomName = "BLE emergency team"
        transport = TRANSPORT; channel = "VOICE"; createdAtMs = System.currentTimeMillis(); receiptElapsedMs = now()
        delivery = if (direction == "OUT") "QUEUED" else "RECEIVED"; wireBytes = bytes
        if (direction == "OUT") playback = "NONE"
    }
    private suspend fun receipt(original: RelayMessage, kind: RelayKind) {
        if (!state.value.active || ledger.pending.size >= 64) return
        val audience = audienceKey()
        val message = withContext(Dispatchers.IO) { RelayPacket.create(kind, original.language, "", identity.publicKey,
            original.id, original.sender, identity.messageId(), identity::sign) }
        if (audience.isBlank() || audience != audienceKey()) return
        ledger.accept(message, 0, RelayPacket.TTL_MS, now()); persist(); update()
    }
    suspend fun played(row: MessageEntity) {
        store.mutate(row.key) { it.playback = "PLAYED" }
        ledger.pending[row.key.removePrefix("ble:")]?.message?.let { receipt(it, RelayKind.PLAYED) }
    }
    suspend fun acknowledge(row: MessageEntity) {
        store.mutate(row.key) { it.humanAcknowledged = true }
        ledger.pending[row.key.removePrefix("ble:")]?.message?.let { receipt(it, RelayKind.ACKNOWLEDGED) }
    }
    private fun update() { mutable.update { it.copy(nearby = peers.size, queued = ledger.pending.size) } }
    suspend fun clearPending() { ledger.pending.clear(); persist(); update() }
    private suspend fun expire() {
        for (pending in ledger.expire(now())) if (pending.message.kind == RelayKind.SOS && pending.hops == 0)
            store.mutate(rowKey(pending.message)) { if (it.delivery in listOf("QUEUED", "RELAYED")) it.delivery = "EXPIRED" }
    }
    private suspend fun persist() = persistence.withLock {
        val secret = key ?: return@withLock
        try {
        val objectValue = JSONObject().put("schema", 1).put("team", state.value.teamId)
            .put("boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
            .put("seen", JSONArray(ledger.seen.toList()))
        val windows = JSONObject()
        ledger.windows.forEach { (sender, window) -> windows.put(sender, JSONObject().put("highest", window.highest).put("bitmap", window.bitmap)) }
        objectValue.put("windows", windows)
        val entries = JSONArray()
        ledger.pending.values.forEach { pending ->
            val remaining = pending.deadline - now()
            if (remaining > 0) entries.put(JSONObject().put("deadline", pending.deadline).put("hops", pending.hops)
                .put("frame", Base64.encodeToString(RelayPacket.seal(RelayPacket(pending.message, maxOf(1, pending.hops),
                    remaining.coerceAtMost(RelayPacket.TTL_MS)), secret), Base64.NO_WRAP)))
        }
        val bytes = objectValue.put("entries", entries).toString().toByteArray()
        withContext(Dispatchers.IO) {
            synchronized(storage) {
                val output = storage.startWrite()
                try { output.write(bytes); storage.finishWrite(output) } catch (error: Exception) { storage.failWrite(output); throw error }
            }
        }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(detail = "Relay queue could not be saved. Keep the app open; pending alerts may be lost after restart.") }
        }
    }
    suspend fun recover() {
        ready.await()
        for (row in store.messages.value.filter(::owns)) {
            store.mutate(row.key) {
                it.receiptElapsedMs = 0; it.firstSendElapsedMs = 0
                it.playback = PlaybackQueue.recoveredStatus(it.playback)
                if (it.direction == "OUT" && it.delivery in listOf("QUEUED", "RELAYED") && row.key.removePrefix("ble:") !in ledger.pending) it.delivery = "EXPIRED"
            }
        }
    }
    private suspend fun restore(secret: ByteArray) {
        val saved = withContext(Dispatchers.IO) {
            if (!storage.baseFile.exists()) return@withContext null
            require(storage.baseFile.length() <= 512 * 1024)
            JSONObject(storage.openRead().bufferedReader().use { it.readText() })
        } ?: return
        require(saved.getInt("schema") == 1)
        if (saved.getString("team") != state.value.teamId) return
        val seen = saved.getJSONArray("seen")
        for (i in 0 until minOf(1024, seen.length())) ledger.seen += seen.getString(i)
        val windows = saved.getJSONObject("windows")
        require(windows.length() <= 128)
        windows.keys().forEach { sender ->
            val window = windows.getJSONObject(sender)
            ledger.windows[sender] = RelayLedger.Window(window.getLong("highest"), window.getLong("bitmap"))
        }
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0 || boot != saved.getInt("boot")) return // Never extend pending alerts across reboot.
        val entries = saved.getJSONArray("entries")
        for (i in 0 until minOf(64, entries.length())) {
            val entry = entries.getJSONObject(i); val deadline = entry.getLong("deadline")
            val remaining = deadline - now()
            if (remaining !in 1..RelayPacket.TTL_MS) continue
            val packet = RelayPacket.open(Base64.decode(entry.getString("frame"), Base64.NO_WRAP), secret)
            val hops = entry.getInt("hops"); require(hops in 0..RelayPacket.MAX_HOPS)
            ledger.pending[packet.message.key] = RelayPending(packet.message, hops, deadline)
        }
        update()
    }
}
