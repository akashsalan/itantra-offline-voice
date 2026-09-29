package org.itantra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.itantra.app.core.LanguageCode
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import org.itantra.app.protocol.RadioSession
import org.itantra.app.protocol.Wire
import org.itantra.app.transport.*
import org.itantra.protocol.v1.MessageType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real protobuf/Room/retry logic over a deterministic fake link, NOT Wi-Fi radio evidence. */
@RunWith(AndroidJUnit4::class)
class ProtocolSessionTest {
    private class FakeLink : LinkTransport {
        val mutable = MutableStateFlow<LinkState>(LinkState.Disconnected)
        override val state = mutable.asStateFlow()
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var other: FakeLink
        var dropFirstDeliveryAck = false
        override suspend fun discover(): Flow<Peer> = emptyFlow()
        override suspend fun connect(peer: Peer) { mutable.value = LinkState.Connected(peer) }
        override suspend fun disconnect() { mutable.value = LinkState.Disconnected }
        override fun incoming(): Flow<ByteArray> = received.receiveAsFlow()
        override suspend fun send(frame: ByteArray): SendResult {
            withTimeout(2000) { while (other.state.value !is LinkState.Connected) delay(5) }
            if (dropFirstDeliveryAck && Wire.decode(frame).type == MessageType.ACK_DELIVERED) dropFirstDeliveryAck = false
            else other.received.send(frame.copyOf())
            return SendResult(frame.size + 4)
        }
    }
    private suspend fun waitFor(test: () -> Boolean) = withTimeout(12000) { while (!test()) delay(20) }
    @Test fun twoSessionsRetryDedupAndThreeAcknowledgementStates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nameA = "test-radio-a-${UUID.randomUUID()}.db"
        val nameB = "test-radio-b-${UUID.randomUUID()}.db"
        val storeA = MessageStore(context, nameA); val storeB = MessageStore(context, nameB)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val linkA = FakeLink(); val linkB = FakeLink()
        linkA.other = linkB; linkB.other = linkA; linkB.dropFirstDeliveryAck = true
        val settingsA = LocalSettings(deviceId = UUID.randomUUID().toString(), name = "Test A")
        val settingsB = LocalSettings(deviceId = UUID.randomUUID().toString(), name = "Test B")
        val receipts = AtomicInteger()
        try {
            storeA.refresh(); storeB.refresh()
            val a = RadioSession(linkA, storeA, { settingsA }, { listOf(LanguageCode.EN, LanguageCode.HI) }, scope, Diagnostics()) {}
            val b = RadioSession(linkB, storeB, { settingsB }, { listOf(LanguageCode.EN) }, scope, Diagnostics()) { receipts.incrementAndGet() }
            linkA.connect(Peer("b", "Test B")); linkB.connect(Peer("a", "Test A"))
            waitFor { a.state.value.code.isNotBlank() && b.state.value.code.isNotBlank() }
            assertEquals(a.state.value.code, b.state.value.code)
            assertFalse(a.state.value.ready)
            a.confirmPairing(); b.confirmPairing()
            waitFor { a.state.value.ready && b.state.value.ready }
            a.enqueue("मुख्य सड़क बंद है।", LanguageCode.HI, false)
            waitFor { storeA.messages.value.any { it.delivery == "DELIVERED" } }
            val outgoing = storeA.messages.value.single()
            assertEquals(2, outgoing.attempts) // First delivery ACK was deliberately lost.
            assertEquals(1, receipts.get())
            val incoming = storeB.messages.value.single()
            assertEquals("hi", incoming.language)
            assertEquals("मुख्य सड़क बंद है।", incoming.text)
            assertEquals("PENDING", incoming.playback)
            b.played(incoming, 15, 200)
            waitFor { storeA.messages.value.single().delivery == "PLAYED" }
            b.acknowledge(incoming.key)
            waitFor { storeA.messages.value.single().delivery == "ACKNOWLEDGED" }
            assertTrue(storeB.messages.value.single().humanAcknowledged)
            b.enqueue("Move north.", LanguageCode.EN, true)
            waitFor { storeA.messages.value.any { it.direction == "IN" } }
            assertTrue(storeA.messages.value.single { it.direction == "IN" }.emergency)
            assertTrue(a.state.value.remoteChat)
            a.enqueue("A silent text chat.", LanguageCode.EN, false, chat = true)
            waitFor { storeB.messages.value.any { it.channel == "CHAT" } }
            val chat = storeB.messages.value.single { it.channel == "CHAT" }
            assertEquals("A silent text chat.", chat.text)
            assertEquals("NOT_APPLICABLE", chat.playback)
            assertFalse(chat.emergency)
            waitFor { storeA.messages.value.any { it.channel == "CHAT" && it.delivery == "DELIVERED" } }
        } finally {
            job.cancelAndJoin()
            storeA.close(); storeB.close()
            context.deleteDatabase(nameA); context.deleteDatabase(nameB) // Only databases created by this test.
        }
    }
    @Test fun roomOutboxAndDedupSurviveReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-persistence-${UUID.randomUUID()}.db"
        var store = MessageStore(context, name)
        try {
            val message = MessageEntity().apply {
                key = "test-peer:OUT:77"; peerId = "test-peer"; messageId = 77
                direction = "OUT"; sequence = 1; text = "Help"; language = "en"
            }
            assertTrue(store.insert(message))
            assertFalse(store.insert(message))
            store.close()
            store = MessageStore(context, name); store.refresh()
            assertEquals(1, store.messages.value.size)
            assertEquals("QUEUED", store.messages.value.single().delivery)
            assertEquals(77L, store.messages.value.single().messageId)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun versionOneHistoryMigratesWithoutLosingMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-migration-" + UUID.randomUUID() + ".db"
        val textFields = listOf("key", "peerId", "peerName", "direction", "text", "language", "delivery", "playback", "transport")
        val numberFields = listOf("messageId", "sessionId", "sequence", "emergency", "createdAtMs", "receiptElapsedMs",
            "speechEndElapsedMs", "attempts", "nextAttemptMs", "firstSendElapsedMs", "wireBytes", "humanAcknowledged")
        context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null).use { db ->
            val columns = textFields.map { "[$it] TEXT NOT NULL" } + numberFields.map { "[$it] INTEGER NOT NULL" }
            db.execSQL("CREATE TABLE messages (" + columns.joinToString(",") + ", PRIMARY KEY([key]))")
            val values = android.content.ContentValues().apply {
                textFields.forEach { put(it, "") }; numberFields.forEach { put(it, 0L) }
                put("key", "old-message"); put("text", "Keep my existing history"); put("language", "en")
                put("direction", "IN"); put("delivery", "DELIVERED"); put("playback", "PLAYED")
            }
            db.insertOrThrow("messages", null, values)
            db.version = 1
        }
        val store = MessageStore(context, name)
        try {
            store.refresh()
            assertEquals("Keep my existing history", store.messages.value.single().text)
            assertEquals("VOICE", store.messages.value.single().channel)
            assertEquals("PLAYED", store.messages.value.single().playback)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
