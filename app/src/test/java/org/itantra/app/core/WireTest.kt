package org.itantra.app.core
import org.itantra.app.protocol.DeliveryPolicy
import org.itantra.app.protocol.Wire
import org.itantra.protocol.v1.Envelope
import org.itantra.protocol.v1.MessageType
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
class WireTest {
    private fun packet() = Envelope.newBuilder().setProtocolMajor(1).setSessionId(21)
        .setMessageId(42).setSequence(1).setType(MessageType.TEXT_FINAL)
        .setLanguage(Wire.language(LanguageCode.HI)).setText("मुख्य सड़क बंद है।")
    @Test fun unicodeCrcAndFramingRoundTrip() {
        val payload = Wire.encode(packet())
        val stream = ByteArrayOutputStream()
        Wire.write(stream, payload)
        assertEquals(packet().text, Wire.decode(Wire.read(ByteArrayInputStream(stream.toByteArray()))).text)
        assertEquals(payload.size + 4, stream.size())
    }
    @Test fun allTenLanguageCodesRoundTrip() { LanguageCode.entries.forEach { assertEquals(it, Wire.language(Wire.language(it))) } }
    @Test(expected = IllegalArgumentException::class) fun corruptCrcRejected() {
        Wire.decode(Envelope.parseFrom(Wire.encode(packet())).toBuilder().setText("bad").build().toByteArray())
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedLengthRejectedBeforePayloadRead() {
        Wire.read(ByteArrayInputStream(byteArrayOf(0, 1, 0, 1)))
    }
    @Test(expected = IllegalArgumentException::class) fun negativeLengthRejected() { Wire.read(ByteArrayInputStream(byteArrayOf(-1,-1,-1,-1))) }
    @Test(expected = IllegalArgumentException::class) fun unknownLanguageRejected() { Wire.decode(Wire.encode(packet().setLanguageValue(99))) }
    @Test(expected = IllegalArgumentException::class) fun incompatibleMajorRejected() { Wire.decode(Wire.encode(packet().setProtocolMajor(2))) }
    @Test(expected = IllegalArgumentException::class) fun oversizedUtf8TextRejected() { Wire.decode(Wire.encode(packet().setText("क".repeat(683)))) }
    @Test fun pairingCodeMatchesBothDirectionsAndChangesWithNonce() {
        val a = Wire.confirmation("a", byteArrayOf(1), "b", byteArrayOf(2))
        assertEquals(a, Wire.confirmation("b", byteArrayOf(2), "a", byteArrayOf(1)))
        assertNotEquals(a, Wire.confirmation("a", byteArrayOf(3), "b", byteArrayOf(2)))
    }
    @Test fun retryScheduleAndMonotonicAcks() {
        assertEquals(4, DeliveryPolicy.MAX_SENDS)
        assertEquals(listOf(1000L,2000L,4000L), (1..3).map(DeliveryPolicy::waitAfterAttempt))
        assertEquals("PLAYED", DeliveryPolicy.advance("PLAYED", "DELIVERED"))
        assertEquals("ACKNOWLEDGED", DeliveryPolicy.advance("DELIVERED", "ACKNOWLEDGED"))
        assertEquals("DELIVERED", DeliveryPolicy.advance("FAILED", "DELIVERED"))
    }
}
