package org.itantra.app.core

import com.google.protobuf.ByteString
import org.itantra.app.lan.*
import org.itantra.app.protocol.Wire
import org.itantra.protocol.lan.*
import org.itantra.protocol.v1.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LanRulesTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val room = UUID.randomUUID().toString()
    private fun member(id: String, online: Boolean = true) = LanMember.newBuilder().setId(id).setName(id.take(1)).setOnline(online).build()
    private fun data(dm: String = "", chat: Boolean = false): LanPacket {
        val envelope = Envelope.newBuilder().setProtocolMajor(1).setProtocolMinor(1).setSessionId(10).setMessageId(20)
            .setSequence(1).setType(MessageType.TEXT_FINAL).setLanguage(org.itantra.protocol.v1.LanguageCode.HI).setText("नमस्ते")
        if (chat) envelope.body = TextOptionsBody.newBuilder().setSilentChat(true).build().toByteString()
        return LanPacket.newBuilder().setKind(LanKind.DATA).setRoomId(room).setSenderId(a).setRecipientId(dm)
            .addAllTargets(if (dm.isBlank()) listOf(b, c) else listOf(dm)).setEnvelope(ByteString.copyFrom(Wire.encode(envelope))).build()
    }
    @Test fun groupNameAcceptsNativeUnicodeAndRejectsEmpty() {
        assertEquals("बचाव दल", LanRules.name("  बचाव   दल  "))
        assertThrows(IllegalArgumentException::class.java) { LanRules.name(" ") }
        assertThrows(IllegalArgumentException::class.java) { LanRules.name("a".repeat(49)) }
    }
    @Test fun groupAudienceExcludesSenderAndOfflineMembers() {
        assertEquals(listOf(b), LanRules.audience(a, listOf(member(a), member(b), member(c, false)), ""))
    }
    @Test fun directAudienceContainsOnlySelectedMember() {
        assertEquals(listOf(c), LanRules.audience(a, listOf(member(a), member(b), member(c)), c))
        assertThrows(IllegalArgumentException::class.java) { LanRules.audience(a, listOf(member(a), member(b, false)), b) }
    }
    @Test fun silentDirectCannotBecomeBroadcastOrVoice() {
        LanRules.validateData(data(b, true), room, a, setOf(a, b, c))
        assertThrows(IllegalArgumentException::class.java) { LanRules.validateData(data(b), room, a, setOf(a, b)) }
        assertThrows(IllegalArgumentException::class.java) { LanRules.validateData(data(b, true).toBuilder().addTargets(c).build(), room, a, setOf(a, b, c)) }
    }
    @Test fun certificateIdentityCannotBeSpoofed() {
        assertThrows(IllegalArgumentException::class.java) { LanRules.validateData(data(), room, b, setOf(a, b, c)) }
    }
    @Test fun wrongRoomAndUnknownRecipientsRejected() {
        assertThrows(IllegalArgumentException::class.java) { LanRules.validateData(data(), UUID.randomUUID().toString(), a, setOf(a, b, c)) }
        assertThrows(IllegalArgumentException::class.java) { LanRules.validateData(data(), room, a, setOf(a, b)) }
    }
    @Test fun retriesCannotChangeMessageOrAudience() {
        assertTrue(LanRules.sameMessage(data(), data()))
        assertFalse(LanRules.sameMessage(data(), data().toBuilder().clearTargets().addTargets(b).build()))
    }
    @Test fun framedUnicodeAndChecksumRoundTrip() {
        val bytes = LanWire.encode(data())
        assertEquals("नमस्ते", Wire.decode(LanWire.decode(bytes).envelope.toByteArray()).text)
        val changed = LanPacket.parseFrom(bytes).toBuilder().setRoomName("tampered").build().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { LanWire.decode(changed) }
    }
    @Test fun endpointMustBeNumericPrivateAndOnLink() {
        assertTrue(LanRules.onLink("192.168.43.2", "192.168.43.1", 24))
        assertTrue(LanRules.onLink("172.20.10.2", "172.20.10.1", 28))
        listOf("example.com", "8.8.8.8", "127.0.0.1", "192.168.44.2", "256.1.1.1").forEach {
            assertFalse(LanRules.onLink(it, "192.168.43.1", 24))
        }
    }
    @Test fun passwordVerificationUsesSaltAndRejectsWrongPassword() {
        val first = AdmissionSecret.create("a strong test password")
        val second = AdmissionSecret.create("a strong test password")
        assertFalse(first.verifier.contentEquals(second.verifier))
        assertTrue(first.matches("a strong test password"))
        assertFalse(first.matches("incorrect password"))
        assertThrows(IllegalArgumentException::class.java) { AdmissionSecret.create("short") }
    }
}
