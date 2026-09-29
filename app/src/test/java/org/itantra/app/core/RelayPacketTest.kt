package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.ECGenParameterSpec

class RelayPacketTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun message() = RelayPacket.create(RelayKind.SOS, "hi", "मदद चाहिए", pair.public.encoded) { bytes ->
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
    }
    @Test fun authenticatedPacketRoundTripsAtMinimumMtu() {
        val original = message(); val wire = RelayPacket.seal(RelayPacket(original, 1, 300000), key)
        val assembler = RelayFragments.Assembler(); var result: ByteArray? = null
        RelayFragments.split(wire, 23, 42).forEach { result = assembler.accept(it, 100) ?: result }
        assertArrayEquals(wire, result); val received = RelayPacket.open(result!!, key)
        assertEquals(original.key, received.message.key); assertEquals(original.text, received.message.text)
    }
    @Test fun wrongTeamOrModifiedFrameIsRejected() {
        val wire = RelayPacket.seal(RelayPacket(message(), 1, 300000), key)
        assertTrue(runCatching { RelayPacket.open(wire, ByteArray(32)) }.isFailure)
        wire[30] = (wire[30].toInt() xor 1).toByte()
        assertTrue(runCatching { RelayPacket.open(wire, key) }.isFailure)
    }
    @Test fun rejectsExpiredOrTooManyHopsAndOutOfOrderFragments() {
        assertTrue(runCatching { RelayPacket.seal(RelayPacket(message(), 4, 100), key) }.isFailure)
        assertTrue(runCatching { RelayPacket.seal(RelayPacket(message(), 1, 0), key) }.isFailure)
        val parts = RelayFragments.split(ByteArray(100), 23, 1)
        assertTrue(runCatching { RelayFragments.Assembler().accept(parts[1], 0) }.isFailure)
    }
}
