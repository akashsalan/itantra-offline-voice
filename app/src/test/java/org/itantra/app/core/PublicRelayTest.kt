package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.ECGenParameterSpec

class PublicRelayTest {
    private val wall = 1_800_000_000_000L
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun message(id: Int = 1, created: Long = wall, text: String = "मदद चाहिए", kind: RelayKind = RelayKind.SOS,
        reference: String = "", recipient: String = "") = PublicRelayPacket.create(kind, "hi", text, pair.public.encoded,
        id.toString(16).padStart(16, '0') + "0123456789abcdef", created, reference, recipient) { bytes ->
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
    }
    private fun wire(signed: PublicRelayMessage = message(), hops: Int = 1, remaining: Long = 300_000) =
        PublicRelayPacket.encode(PublicRelayPacket(signed, hops, remaining))

    @Test fun publicPacketWorksWithoutTeamCodeAndAtMinimumMtu() {
        val original = message(); val frame = wire(original)
        val assembler = RelayFragments.Assembler(); var assembled: ByteArray? = null
        RelayFragments.split(frame, 23, 17).forEach { assembled = assembler.accept(it, 100) ?: assembled }
        val decoded = PublicRelayPacket.decode(assembled!!, wall)
        assertEquals(original.key, decoded.signed.key)
        assertEquals("मदद चाहिए", decoded.signed.message.text)
        assertEquals(wall, decoded.signed.createdAtMs)
    }
    @Test fun publicAndPrivateProtocolsCannotBeInterchanged() {
        val secret = ByteArray(32) { 7 }
        val privateMessage = RelayPacket.create(RelayKind.SOS, "en", "Help", pair.public.encoded) { bytes ->
            Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
        }
        val privateFrame = RelayPacket.seal(RelayPacket(privateMessage, 1, 300_000), secret)
        assertTrue(runCatching { PublicRelayPacket.decode(privateFrame, wall) }.isFailure)
        assertTrue(runCatching { RelayPacket.open(wire(), secret) }.isFailure)
    }
    @Test fun changedWordsLanguageSenderOrSignedTimestampAreRejected() {
        val signed = message()
        listOf(signed.copy(message = signed.message.copy(text = "different")),
            signed.copy(message = signed.message.copy(language = "en")),
            signed.copy(createdAtMs = wall + 1000)).forEach {
            assertTrue(runCatching { PublicRelayPacket.decode(wire(it), wall) }.isFailure)
        }
        val damaged = wire(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        assertTrue(runCatching { PublicRelayPacket.decode(damaged, wall) }.isFailure)
    }
    @Test fun expiryIsSignedAndCannotBeExtendedByResettingTransitBudget() {
        assertTrue(runCatching { PublicRelayPacket.decode(wire(), wall + 300_000) }.isFailure)
        assertEquals(1000L, PublicRelayPacket.decode(wire(remaining = 300_000), wall + 299_000).remainingMs)
        assertTrue(runCatching { PublicRelayPacket.decode(wire(message(created = wall + 120_001)), wall) }.isFailure)
        assertTrue(runCatching { wire(hops = 4) }.isFailure)
        assertTrue(runCatching { wire(remaining = 0) }.isFailure)
    }
    @Test fun malformedOversizedAndTrailingDataAreRejected() {
        assertTrue(runCatching { message(text = "x".repeat(1025)) }.isFailure)
        assertTrue(runCatching { message(text = "!!!") }.isFailure)
        assertTrue(runCatching { PublicRelayPacket.decode(wire() + byteArrayOf(0), wall) }.isFailure)
        assertTrue(runCatching { PublicRelayPacket.decode(ByteArray(2049), wall) }.isFailure)
        for (size in listOf(0, 15, 64, 100)) assertTrue(runCatching { PublicRelayPacket.decode(wire().copyOf(size), wall) }.isFailure)
    }
    @Test fun fourPhoneChainRelaysAutomaticallyButFourthPhoneCannotForward() {
        val phones = List(4) { PublicRelayLedger() }
        val signed = message()
        assertTrue(phones[0].accept(signed, 0, 300_000, 0, wall))
        for (hop in 1..3) {
            val next = phones[hop - 1].candidates("phone$hop", hop * 1000L, wall + hop * 1000).single()
            val remaining = next.deadline - hop * 1000L - 30_000
            val packet = PublicRelayPacket.decode(wire(next.signed, next.hops + 1, remaining), wall + hop * 1000)
            assertEquals(hop, packet.hops)
            assertTrue(phones[hop].accept(packet.signed, packet.hops, packet.remainingMs, hop * 1000L, wall + hop * 1000))
        }
        assertEquals(3, phones[3].pending.values.single().hops)
        assertTrue(phones[3].candidates("phoneE", 4000, wall + 4000).isEmpty())
    }
    @Test fun duplicatesLoopsAndOwnReturningAlertAreRejected() {
        val ledger = PublicRelayLedger(); val signed = message()
        assertTrue(ledger.accept(signed, 0, 300_000, 0, wall))
        assertFalse(ledger.accept(signed, 2, 240_000, 5000, wall + 5000))
        assertEquals(1, ledger.pending.size)
    }
    @Test fun noForwardAfterConsentStoppedAndRestartDoesNotRequeueOldAlert() {
        val ledger = PublicRelayLedger(); val signed = message()
        assertTrue(ledger.accept(signed, 1, 300_000, 0, wall))
        ledger.stop()
        assertTrue(ledger.candidates("B", 1000, wall + 1000).isEmpty())
        assertFalse(ledger.accept(signed, 1, 299_000, 1000, wall + 1000))
        assertTrue(ledger.accept(message(2), 1, 299_000, 1000, wall + 1000))
    }
    @Test fun retriesAreBoundedAndSuccessfulPeerIsNotFloodedAgain() {
        val ledger = PublicRelayLedger(); val signed = message()
        ledger.accept(signed, 1, 300_000, 0, wall)
        repeat(3) { attempt ->
            val now = attempt * 30_000L
            assertEquals(1, ledger.candidates("B", now, wall + now).size)
            ledger.attempting(signed.key, "B", now)
            assertTrue(ledger.candidates("B", now + 1, wall + now + 1).isEmpty())
        }
        assertTrue(ledger.candidates("B", 90_000, wall + 90_000).isEmpty())
        ledger.sent(signed.key, "C")
        assertTrue(ledger.candidates("C", 90_000, wall + 90_000).isEmpty())
    }
    @Test fun originRateLimitSurvivesToggleAndClearsAfterOneMinute() {
        val ledger = PublicRelayLedger()
        assertTrue(ledger.accept(message(1), 1, 300_000, 0, wall))
        assertTrue(ledger.accept(message(2), 1, 300_000, 1000, wall + 1000))
        assertFalse(ledger.accept(message(3), 1, 300_000, 2000, wall + 2000))
        ledger.stop()
        assertFalse(ledger.accept(message(3), 0, 300_000, 3000, wall + 3000))
        assertTrue(ledger.accept(message(3), 1, 240_000, 60_000, wall + 60_000))
    }
    @Test fun duplicateReceiptsCannotInflateCountsByChangingMessageId() {
        val ledger = PublicRelayLedger(); val original = message()
        fun receipt(id: Int) = message(id, text = "", kind = RelayKind.DELIVERED,
            reference = original.message.id, recipient = original.message.sender)
        assertTrue(ledger.accept(receipt(2), 1, 300_000, 0, wall))
        assertFalse(ledger.accept(receipt(3), 1, 300_000, 1, wall))
    }
    @Test fun elapsedTimeExpiryDoesNotDependOnWallClockGoingBackwards() {
        val ledger = PublicRelayLedger(); val signed = message()
        ledger.accept(signed, 1, 60_000, 0, wall)
        assertTrue(ledger.candidates("B", 60_000, wall - 10_000).isEmpty())
        assertEquals(1, ledger.expire(60_000).size)
    }
    @Test fun globalLimitRejectsFloodWithoutEvictingAcceptedAlerts() {
        val ledger = PublicRelayLedger()
        repeat(12) { index ->
            // Ledger trusts a verified packet; vary the device here to exercise admission independently of crypto.
            val signed = message(index + 1).let { it.copy(message = it.message.copy(publicKey = ByteArray(90) { index.toByte() })) }
            assertTrue(ledger.accept(signed, 1, 300_000, 0, wall))
        }
        assertFalse(ledger.accept(message(30), 1, 300_000, 0, wall))
        assertEquals(12, ledger.pending.size)
        assertTrue(ledger.accept(message(31), 0, 300_000, 0, wall)) // Local SOS retains reserved capacity.
    }
    @Test fun hopThreeCanBeStoredButNotForwardedAndInvalidAdmissionIsRejected() {
        val ledger = PublicRelayLedger()
        assertTrue(ledger.accept(message(), 3, 300_000, 0, wall))
        assertTrue(ledger.candidates("E", 0, wall).isEmpty())
        assertFalse(ledger.accept(message(2), 4, 300_000, 0, wall))
        assertFalse(ledger.accept(message(2), 1, 0, 0, wall))
        assertFalse(ledger.accept(message(2), 1, 300_000, 0, wall + 300_000))
    }
}
