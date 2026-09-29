package org.itantra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.itantra.app.core.LanguageCode
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import org.itantra.app.lan.*
import org.itantra.app.protocol.Wire
import org.itantra.protocol.lan.*
import org.itantra.protocol.v1.MessageType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Real AndroidKeyStore/TLS/Room over local sockets, NOT evidence of multiple physical Wi-Fi radios. */
@RunWith(AndroidJUnit4::class)
class LanSessionTest {
    private class Loopback : LanNetworkAccess {
        private val local = LanAddress("127.0.0.1", 8, null, null)
        override fun inspect() = LanNetworkStatus(listOf(local))
        override fun matching(address: String): LanAddress { require(address == local.ip); return local }
        override fun connect(address: String, port: Int): Socket {
            matching(address)
            return Socket().apply { connect(InetSocketAddress(address, port), 3000) }
        }
    }
    private class Node(val store: MessageStore, val session: LanSession, val discovery: LanDiscovery,
        val name: String, val alias: String, val received: AtomicInteger)
    private class Fixture(private val routes: LanNetworkAccess = Loopback(), private val presence: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val nodes = mutableListOf<Node>()
        suspend fun node(label: String, dropFirstAck: Boolean = false): Node {
            val id = UUID.randomUUID().toString()
            val database = "test-lan-$id.db"
            val alias = "test-lan-$id"
            val store = MessageStore(context, database).also { it.refresh() }
            val discovery = LanDiscovery(context, scope, routes, publishServices = presence)
            val count = AtomicInteger()
            val drop = AtomicBoolean(dropFirstAck)
            val session = LanSession(context, scope, store, { label }, discovery, Diagnostics(), { count.incrementAndGet() }, alias) { socket, packet ->
                val ack = packet.kind == LanKind.RECEIPT && Wire.decode(packet.envelope.toByteArray()).type == MessageType.ACK_DELIVERED
                if (!ack || !drop.compareAndSet(true, false)) Wire.write(socket.outputStream, LanWire.encode(packet))
            }
            return Node(store, session, discovery, database, alias, count).also(nodes::add)
        }
        suspend fun close() = withContext(NonCancellable) {
            nodes.asReversed().forEach { it.session.disconnect(); it.discovery.stop() }
            job.cancelAndJoin()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            nodes.forEach {
                it.store.close()
                context.deleteDatabase(it.name)
                context.deleteSharedPreferences("itantra-lan-rooms-${it.alias}")
                keys.deleteEntry(it.alias) // Only this fixture's unique test identities, never the app identity.
            }
        }
    }
    private suspend fun waitFor(label: String, condition: () -> Boolean) {
        try { withTimeout(25000) { while (!condition()) delay(30) } }
        catch (timeout: TimeoutCancellationException) { throw AssertionError("Timed out: $label", timeout) }
    }
    private suspend fun join(node: Node, host: Node, password: String, address: String = "127.0.0.1") {
        val room = host.session.state.value
        node.session.join(NearbyRoom(room.roomId, room.name, "Test creator", address, LanRules.PORT, room.group, room.online, 0), "Android socket test")
        waitFor("host confirmation: ${node.alias}") { node.session.state.value.confirmCode.isNotBlank() || !node.session.state.value.active }
        assertEquals("Join error: ${node.session.state.value}", LanRules.code(room.selfId), node.session.state.value.confirmCode)
        node.session.confirmHost(password)
    }

    @Test fun threeLocalSessionsRetryDedupBroadcastDmAndReceipts() = runBlocking {
        val f = Fixture()
        try {
            val host = f.node("Creator")
            val a = f.node("Hindi sender")
            val b = f.node("English receiver", dropFirstAck = true)
            host.session.host("राहत टीम", "rescue-team-42", true, "Android loopback test")
            join(a, host, "rescue-team-42")
            waitFor("first member admitted") { a.session.state.value.ready }
            join(b, host, "rescue-team-42")
            waitFor("all three rosters") { f.nodes.all { it.session.state.value.online == 3 && it.session.state.value.ready } }
            assertEquals("राहत टीम", b.session.state.value.name)
            a.session.enqueue("मुख्य सड़क बंद है।", LanguageCode.HI, false)
            waitFor("per-recipient delivery after a deliberately dropped ACK") {
                a.store.messages.value.any { it.direction == "OUT" && LanRules.ids(it.deliveredTo).size == 2 }
            }
            assertEquals(1, host.received.get())
            assertEquals(1, b.received.get()) // A retry cannot enqueue a second TTS job.
            assertFalse(a.store.messages.value.any { it.direction == "IN" }) // No sender loopback.
            val incoming = b.store.messages.value.single { it.direction == "IN" }
            assertEquals("hi", incoming.language)
            assertEquals("मुख्य सड़क बंद है।", incoming.text)
            assertEquals("PENDING", incoming.playback)
            val relay = host.store.messages.value.single { it.direction == "RELAY" }
            assertTrue("A missing recipient ACK must cause a wire retry", relay.attempts >= 2)
            // Protocol receipt test only: these explicit calls do not claim any audio was played.
            b.session.played(incoming, 15, 200)
            b.session.acknowledge(incoming.key)
            waitFor("independent per-recipient played and human receipts") {
                a.store.messages.value.single { it.direction == "OUT" }.let {
                    LanRules.ids(it.playedBy).size == 1 && LanRules.ids(it.acknowledgedBy).size == 1
                }
            }
            val bId = b.session.state.value.selfId
            a.session.enqueue("Only you receive this text.", LanguageCode.EN, false, chat = true, recipient = bId)
            waitFor("direct delivery") { b.store.messages.value.any { it.channel == "CHAT" } }
            val dm = b.store.messages.value.single { it.channel == "CHAT" }
            assertEquals("NOT_APPLICABLE", dm.playback)
            assertEquals(bId, dm.targetId)
            assertFalse("Creator stores a hidden relay record, not an incoming DM bubble", host.store.messages.value.any { it.channel == "CHAT" })
            assertEquals("Silent DMs do not invoke the voice callback", 1, b.received.get())
            b.session.markRead(listOf(dm.key))
            assertTrue(b.store.find(dm.key)!!.readLocally)
            host.session.enqueue("Priority alert", LanguageCode.EN, true)
            waitFor("alert fan-out") { a.store.messages.value.any { it.direction == "IN" && it.emergency } && b.received.get() == 2 }
            val originalRoom = host.session.state.value.roomId
            val oldVoice = a.store.messages.value.single { it.direction == "OUT" && it.channel == "VOICE" }
            // Simulate a sender retaining a failed outbox entry after losing its receipt state.
            a.store.mutate(oldVoice.key) { it.delivery = "FAILED"; it.deliveredTo = ""; it.attempts = 4 }
            a.session.disconnect(); b.session.disconnect(); host.session.disconnect()
            host.session.host("", "", true, "Android loopback test", resume = true)
            assertEquals(originalRoom, host.session.state.value.roomId)
            assertEquals("राहत टीम", host.session.state.value.name)
            join(a, host, "rescue-team-42")
            waitFor("saved group rejoin") { a.session.state.value.ready }
            waitFor("failed original-recipient outbox recovers on rejoin") {
                a.store.messages.value.any { it.key == oldVoice.key && LanRules.ids(it.deliveredTo).size == 2 }
            }
            assertEquals(originalRoom, a.session.state.value.roomId)
            assertEquals(1, host.received.get())
        } finally { f.close() }
    }

    @Test fun wrongPasswordPausedJoinsAndDirectConsent() = runBlocking {
        val f = Fixture()
        try {
            val host = f.node("Creator"); val a = f.node("Member")
            host.session.host("Protected group", "correct-password", true, "Android loopback test")
            join(a, host, "wrong-password")
            waitFor("incorrect password rejected") { !a.session.state.value.active }
            assertTrue(a.session.state.value.error.orEmpty().contains("Incorrect group password"))
            assertEquals(1, host.session.state.value.online)
            delay(2100) // Let the intentional failed-admission throttle expire.
            host.session.setAccepting(false)
            join(a, host, "correct-password")
            waitFor("paused joins rejected") { !a.session.state.value.active }
            assertTrue(a.session.state.value.error.orEmpty().contains("paused"))
            a.session.disconnect(); host.session.disconnect(); delay(2100)
            host.session.host("Creator", "", false, "Android loopback test")
            join(a, host, "")
            waitFor("creator consent prompt") { host.session.state.value.requests.isNotEmpty() }
            assertFalse(a.session.state.value.ready)
            host.session.approve(host.session.state.value.requests.single().id, true)
            waitFor("direct approval connected") { a.session.state.value.ready }
            a.session.enqueue("Silent one-to-one text", LanguageCode.EN, false, chat = true)
            waitFor("direct text") { host.store.messages.value.any { it.channel == "CHAT" } }
            assertEquals(0, host.received.get())
        } finally { f.close() }
    }

    @Test fun currentWifiInterfaceDiscoveryAndMessage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val routes = LanNetworks(context)
        org.junit.Assume.assumeTrue("A Wi-Fi or hotspot interface is required for this test", routes.inspect().ready)
        val f = Fixture(routes, presence = true)
        try {
            val host = f.node("Interface test creator"); val client = f.node("Interface test member")
            host.session.host("Temporary iTantra test", "test-password-42", true, "Android Wi-Fi interface test")
            client.discovery.browse(true)
            waitFor("NSD/UDP discovery on the real Wi-Fi interface") { client.discovery.rooms.value.any { it.id == host.session.state.value.roomId } }
            val found = client.discovery.rooms.value.first { it.id == host.session.state.value.roomId }
            assertTrue(LanRules.privateAddress(found.address))
            assertTrue(routes.accepts(found.address))
            join(client, host, "test-password-42", found.address)
            waitFor("real-interface connection") { client.session.state.value.ready }
            client.session.enqueue("Wi-Fi interface text", LanguageCode.EN, false, chat = true)
            waitFor("real-interface ACK") { client.store.messages.value.any { it.direction == "OUT" && it.delivery == "DELIVERED" } }
            assertEquals("Wi-Fi interface text", host.store.messages.value.single { it.direction == "IN" }.text)
        } finally { f.close() }
    }

    @Test fun androidTlsMutualIdentityAndWrongHostPin() = runBlocking {
        val aliases = listOf("test-lan-tls-${UUID.randomUUID()}", "test-lan-tls-${UUID.randomUUID()}")
        val host = LanTls(aliases[0]); val client = LanTls(aliases[1])
        try {
            for (tls12 in listOf(false, true)) for (correctPin in listOf(true, false)) {
                val server = host.context().serverSocketFactory.createServerSocket() as SSLServerSocket
                server.needClientAuth = true
                server.bind(InetSocketAddress("127.0.0.1", 0))
                try {
                    val accepted = async(Dispatchers.IO) {
                        runCatching { (server.accept() as SSLSocket).use { socket ->
                            LanTls.configure(socket); socket.useClientMode = false; socket.needClientAuth = true; socket.startHandshake()
                            assertEquals(client.id, LanTls.peerId(socket)); socket.outputStream.write(7)
                        } }
                    }
                    val result = withContext(Dispatchers.IO) { runCatching {
                        (client.context(if (correctPin) host.id else "0".repeat(64)).socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { socket ->
                            LanTls.configure(socket); socket.useClientMode = true
                            if (tls12) socket.enabledProtocols = arrayOf("TLSv1.2")
                            socket.startHandshake()
                            assertEquals(host.id, LanTls.peerId(socket))
                            assertTrue(socket.session.protocol in listOf("TLSv1.2", "TLSv1.3"))
                            if (tls12) assertEquals("TLSv1.2", socket.session.protocol)
                            assertEquals(7, socket.inputStream.read())
                        }
                    } }
                    if (correctPin) { accepted.await().getOrThrow(); result.getOrThrow() }
                    else { assertTrue("Changed host identity must be rejected", result.isFailure); accepted.await() }
                } finally { server.close() }
            }
        } finally {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach(keys::deleteEntry)
        }
    }
}
