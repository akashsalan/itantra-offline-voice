package org.itantra.app.protocol

import android.os.SystemClock
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.app.BuildConfig
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.UnicodeText
import org.itantra.app.core.PlaybackQueue
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import org.itantra.app.transport.*
import org.itantra.protocol.v1.*
import java.security.SecureRandom
import java.util.UUID

data class SessionStatus(
    val peerId: String = "", val peerName: String = "", val code: String = "",
    val localConfirmed: Boolean = false, val remoteConfirmed: Boolean = false,
    val ready: Boolean = false, val remoteTts: Set<LanguageCode> = emptySet(),
    val remoteChat: Boolean = false,
    val error: String? = null
)

/** Serialized session handling; text is acknowledged only after Room commits it. */
class RadioSession(
    private val transport: LinkTransport, private val store: MessageStore,
    private val settings: () -> LocalSettings, private val installed: () -> List<LanguageCode>,
    private val scope: CoroutineScope, private val diagnostics: Diagnostics,
    private val onReceived: (MessageEntity) -> Unit
) {
    private val random = SecureRandom()
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SessionStatus())
    val state = mutable.asStateFlow()
    private var boundConnection: LinkState.Connected? = null
    private var localSession = id()
    private var remoteSession = 0L
    private var nonce = ByteArray(16).also(random::nextBytes)
    private var localSequence = 0
    private var helloAt = 0L
    private var lastReceived = 0L

    init {
        scope.launch {
            transport.state.collect { link ->
                lock.withLock {
                    if (link is LinkState.Connected) {
                        runCatching { begin(link) }.onFailure { mutable.value = SessionStatus(error = it.message) }
                    } else {
                        boundConnection = null; remoteSession = 0
                        mutable.value = SessionStatus()
                    }
                }
            }
        }
        scope.launch {
            transport.incoming().collect { bytes ->
                try { lock.withLock {
                    val link = transport.state.value as? LinkState.Connected ?: return@withLock
                    begin(link)
                    receive(Wire.decode(bytes), bytes.size + 4)
                } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(ready = false, error = error.message ?: "Invalid peer packet") }
                    diagnostics.put("protocol_error", error.javaClass.simpleName)
                    transport.disconnect()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(200)
                try { lock.withLock { pump() } } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(error = "Delivery paused: ${error.message}") }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                try { lock.withLock {
                    if (transport.state.value is LinkState.Connected) {
                        val now = SystemClock.elapsedRealtime()
                        if (remoteSession == 0L && now - helloAt > 30000) {
                            mutable.update { it.copy(error = "Peer is not responding as iTantra") }
                            transport.disconnect()
                        } else if (lastReceived > 0 && now - lastReceived > 30000) {
                            transport.disconnect()
                        } else send(control(MessageType.PING))
                    }
                } } catch (error: Exception) { if (error is CancellationException) throw error }
            }
        }
    }
    private fun id(): Long { var value: Long; do { value = random.nextLong() } while (value == 0L); return value }
    private fun control(type: MessageType, messageId: Long = id()): Envelope.Builder =
        Envelope.newBuilder().setProtocolMajor(Wire.MAJOR).setProtocolMinor(Wire.MINOR)
            .setSessionId(localSession).setMessageId(messageId).setType(type)
            .setSentElapsedMs(SystemClock.elapsedRealtime())
    private suspend fun send(builder: Envelope.Builder): SendResult = transport.send(Wire.encode(builder))
    private suspend fun begin(link: LinkState.Connected) {
        if (boundConnection === link) return
        val local = settings()
        check(local.deviceId.isNotBlank()) { "Settings are still loading" }
        boundConnection = link; localSession = id(); remoteSession = 0
        nonce = ByteArray(16).also(random::nextBytes)
        mutable.value = SessionStatus(peerName = link.peer.name)
        helloAt = SystemClock.elapsedRealtime(); lastReceived = helloAt
        send(control(MessageType.HELLO).setBody(HelloBody.newBuilder()
            .setDeviceId(local.deviceId).setDeviceName(local.name.take(40))
            .setNonce(ByteString.copyFrom(nonce)).setAppVersion(BuildConfig.VERSION_NAME).build().toByteString()))
        capabilities()
    }
    private suspend fun capabilities() {
        val status = mutable.value
        val body = CapabilitiesBody.newBuilder()
            .addAllAsrLanguages(installed().map(Wire::language))
            .addAllTtsLanguages(LanguageCode.entries.map(Wire::language))
            .addAllTransports(transport.capabilities).setMaxPacketBytes(Wire.MAX_FRAME)
            .setSupportsTextChat(true)
            .setPairingConfirmed(status.localConfirmed).setConfirmationCode(status.code).build()
        send(control(MessageType.CAPABILITIES).setBody(body.toByteString()))
    }
    suspend fun confirmPairing() = lock.withLock {
        check(remoteSession != 0L && mutable.value.code.isNotBlank()) { "Wait for the peer handshake" }
        mutable.update { it.copy(localConfirmed = true) }
        capabilities()
        updateReady()
    }
    private fun updateReady() {
        mutable.update { it.copy(ready = it.localConfirmed && it.remoteConfirmed, error = null) }
    }
    private suspend fun receive(packet: Envelope, wireBytes: Int) {
        lastReceived = SystemClock.elapsedRealtime()
        if (packet.type == MessageType.HELLO) {
            require(remoteSession == 0L || remoteSession == packet.sessionId) { "Unexpected peer session change" }
            val hello = HelloBody.parseFrom(packet.body)
            require(hello.deviceId == UUID.fromString(hello.deviceId).toString()) { "Invalid peer identity" }
            require(hello.deviceId != settings().deviceId) { "Both devices have the same identity" }
            require(hello.deviceName.length in 1..40 && hello.nonce.size() == 16)
            remoteSession = packet.sessionId
            val code = Wire.confirmation(settings().deviceId, nonce, hello.deviceId, hello.nonce.toByteArray())
            mutable.update { it.copy(peerId = hello.deviceId, peerName = hello.deviceName, code = code) }
            capabilities()
            return
        }
        require(remoteSession != 0L && packet.sessionId == remoteSession) { "Packet belongs to a different session" }
        if (packet.type == MessageType.CAPABILITIES) {
            val caps = CapabilitiesBody.parseFrom(packet.body)
            require(caps.maxPacketBytes in 4096..Wire.MAX_FRAME) { "Incompatible packet capability" }
            val voices = caps.ttsLanguagesList.mapNotNull { runCatching { Wire.language(it) }.getOrNull() }.toSet()
            val confirmed = caps.pairingConfirmed && caps.confirmationCode == mutable.value.code
            mutable.update { it.copy(remoteTts = voices, remoteConfirmed = confirmed, remoteChat = caps.supportsTextChat) }
            updateReady()
            return
        }
        if (packet.type == MessageType.PING) return
        require(mutable.value.ready) { "Both users must confirm the matching code before exchanging messages" }
        val peer = mutable.value
        when (packet.type) {
            MessageType.TEXT_FINAL, MessageType.ALERT -> {
                val lang = Wire.language(packet.language)
                require(packet.body.size() <= 64) { "Invalid text options size" }
                val chat = !packet.body.isEmpty && TextOptionsBody.parseFrom(packet.body).silentChat
                require(!chat || packet.type == MessageType.TEXT_FINAL) { "Emergency messages cannot be silent chat" }
                val message = MessageEntity().apply {
                    key = MessageStore.key(peer.peerId, "IN", packet.messageId)
                    peerId = peer.peerId; peerName = peer.peerName; messageId = packet.messageId
                    sessionId = packet.sessionId; sequence = packet.sequence; direction = "IN"
                    text = packet.text; language = lang.code; emergency = packet.type == MessageType.ALERT
                    createdAtMs = System.currentTimeMillis(); receiptElapsedMs = SystemClock.elapsedRealtime()
                    delivery = "DELIVERED"; this.wireBytes = wireBytes
                    this.transport = this@RadioSession.transport.displayName
                    channel = if (chat) "CHAT" else "VOICE"
                    if (chat) playback = "NOT_APPLICABLE"
                }
                val inserted = store.insert(message) // Commit/dedup before ACK.
                if (inserted) onReceived(message)
                send(control(MessageType.ACK_DELIVERED, packet.messageId))
                if (!inserted) {
                    val old = store.find(message.key)
                    if (old?.playback == "PLAYED") send(control(MessageType.ACK_PLAYED, packet.messageId))
                    if (old?.humanAcknowledged == true) send(control(MessageType.ACKNOWLEDGED, packet.messageId))
                }
            }
            MessageType.ACK_DELIVERED, MessageType.ACK_PLAYED, MessageType.ACKNOWLEDGED -> {
                val delivery = when (packet.type) { MessageType.ACK_PLAYED -> "PLAYED"; MessageType.ACKNOWLEDGED -> "ACKNOWLEDGED"; else -> "DELIVERED" }
                val key = MessageStore.key(peer.peerId, "OUT", packet.messageId)
                store.mutate(key) { old ->
                    old.delivery = DeliveryPolicy.advance(old.delivery, delivery)
                }
                diagnostics.acknowledge(key, peer.peerId, packet.type == MessageType.ACK_PLAYED, SystemClock.elapsedRealtimeNanos())
            }
            MessageType.TEXT_PARTIAL -> Unit // Never persisted or spoken.
            MessageType.ERROR -> mutable.update { it.copy(error = "Peer reported a protocol error") }
            else -> Unit
        }
    }
    suspend fun enqueue(text: String, language: LanguageCode, emergency: Boolean, speechEndMs: Long = 0, chat: Boolean = false,
        measurementId: Long? = null, mayEnqueue: () -> Boolean = { true }) = lock.withLock {
        val peer = mutable.value
        check(peer.ready) { "Connect and confirm the matching code on both phones first" }
        require(!chat || (peer.remoteChat && !emergency)) { "Update iTantra on the other phone to use text-only chat" }
        require(chat || language in peer.remoteTts) { "Peer has no advertised ${language.displayName} voice" }
        val chunks = UnicodeText.chunks(text)
        require(chunks.isNotEmpty() && chunks.size <= 8) { "Message is empty or too long" }
        localSequence = maxOf(localSequence, store.nextSequence() - 1)
        val keys = mutableListOf<String>()
        for (chunk in chunks) {
            val number = id()
            localSequence = Math.addExact(localSequence, 1)
            val row = MessageEntity().apply {
                key = MessageStore.key(peer.peerId, "OUT", number)
                peerId = peer.peerId; peerName = peer.peerName; messageId = number
                sessionId = localSession; sequence = localSequence; direction = "OUT"
                this.text = chunk; this.language = language.code; this.emergency = emergency
                createdAtMs = System.currentTimeMillis(); speechEndElapsedMs = speechEndMs
                this.transport = this@RadioSession.transport.displayName
                channel = if (chat) "CHAT" else "VOICE"
                if (chat) playback = "NOT_APPLICABLE"
            }
            store.insert(row, mayEnqueue)
            diagnostics.bind(measurementId, row)
            keys += row.key
        }
        keys.toList()
    }
    private suspend fun pump() {
        val peer = mutable.value
        if (!peer.ready || transport.state.value !is LinkState.Connected) return
        // Priority may preempt normal queue order; each priority retains sequence order.
        val eligible = store.messages.value.filter { it.roomId.isBlank() && it.direction == "OUT" && it.peerId == peer.peerId && it.delivery in listOf("QUEUED", "SENDING") }
        val candidate = eligible.sortedWith(compareByDescending<MessageEntity> { it.emergency }.thenBy { it.sequence }).firstOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        if (candidate.nextAttemptMs > now) return
        if (candidate.attempts >= DeliveryPolicy.MAX_SENDS) {
            store.mutate(candidate.key) { it.delivery = "FAILED" }; return
        }
        val builder = control(if (candidate.emergency) MessageType.ALERT else MessageType.TEXT_FINAL, candidate.messageId)
            .setSequence(candidate.sequence).setLanguage(Wire.language(LanguageCode.fromCode(candidate.language))).setText(candidate.text)
        if (candidate.channel == "CHAT")
            builder.setBody(TextOptionsBody.newBuilder().setSilentChat(true).build().toByteString())
        val bytes = Wire.encode(builder)
        // Persist the attempt before touching the socket. Process-death recovery is idempotent.
        store.mutate(candidate.key) {
            it.attempts++; it.delivery = "SENDING"
            if (it.firstSendElapsedMs == 0L) it.firstSendElapsedMs = now
            it.nextAttemptMs = now + DeliveryPolicy.waitAfterAttempt(it.attempts)
            it.wireBytes = bytes.size + 4
            it.transport = transport.displayName
        }
        val sentTransport = transport.displayName
        transport.send(bytes)
        diagnostics.transmitted(candidate.key, bytes.size + 4, sentTransport)
    }
    suspend fun retry(key: String) = lock.withLock {
        store.mutate(key) { if (it.direction == "OUT" && it.delivery == "FAILED") {
            it.delivery = "QUEUED"; it.attempts = 0; it.nextAttemptMs = 0; it.firstSendElapsedMs = 0
        } }
    }
    suspend fun played(message: MessageEntity, receiptToSubmissionMs: Long, durationMs: Long) = lock.withLock {
        store.mutate(message.key) { it.playback = "PLAYED" }
        if (mutable.value.ready && mutable.value.peerId == message.peerId) {
            val ack = control(MessageType.ACK_PLAYED, message.messageId)
            if (receiptToSubmissionMs >= 0) ack.setBody(AckBody.newBuilder()
                .setReceiptToPlaybackSubmissionMs(receiptToSubmissionMs)
                .setPlaybackDurationMs(durationMs.coerceAtLeast(0)).build().toByteString())
            runCatching { send(ack) } // Local playback remains completed even if the radio drops.
        }
    }
    suspend fun acknowledge(key: String) = lock.withLock {
        val message = store.mutate(key) { if (it.direction == "IN") { it.humanAcknowledged = true; it.delivery = "ACKNOWLEDGED" } } ?: return@withLock
        if (message.direction == "IN" && mutable.value.ready && mutable.value.peerId == message.peerId)
            send(control(MessageType.ACKNOWLEDGED, message.messageId))
    }
    suspend fun recoverOutbox() {
        store.refresh()
        for (message in store.messages.value.filter { it.roomId.isBlank() }) {
            if (message.direction == "OUT" && message.delivery in listOf("QUEUED", "SENDING"))
                store.mutate(message.key) { it.nextAttemptMs = 0; it.firstSendElapsedMs = 0; it.speechEndElapsedMs = 0 }
            if (message.direction == "IN" && message.playback != PlaybackQueue.recoveredStatus(message.playback))
                store.mutate(message.key) { it.playback = PlaybackQueue.recoveredStatus(it.playback) }
            if (message.direction == "IN")
                store.mutate(message.key) { it.receiptElapsedMs = 0 } // Prior-process timestamps may belong to another boot.
        }
    }
}
