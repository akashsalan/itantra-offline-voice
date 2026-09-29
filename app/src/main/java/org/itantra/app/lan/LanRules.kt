package org.itantra.app.lan

import org.itantra.app.core.UnicodeText
import org.itantra.app.protocol.Wire
import org.itantra.protocol.lan.*
import org.itantra.protocol.v1.MessageType
import org.itantra.protocol.v1.TextOptionsBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Pure, testable admission, addressing and audience rules shared by every LAN role. */
object LanRules {
    const val VERSION = 1
    const val PORT = 38774
    const val DISCOVERY_PORT = 38775
    const val SERVICE = "_itantra-lan._tcp."
    const val MAX_MEMBERS = 8
    fun identity(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    fun room(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    fun name(value: String): String {
        val result = UnicodeText.normalize(value)
        require(result.isNotBlank() && result.length <= 48 && result.toByteArray(Charsets.UTF_8).size <= 192 &&
            result.none { it.isISOControl() }) { "Use a name of 1–48 characters, without control characters." }
        return result
    }
    fun ids(value: String): Set<String> = value.split(',').filter { identity(it) }.toSet()
    fun joinIds(value: Collection<String>) = value.distinct().sorted().joinToString(",")
    fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun code(fingerprint: String) = fingerprint.take(16).uppercase().chunked(4).joinToString(" ")
    fun audience(self: String, members: List<LanMember>, recipient: String): List<String> {
        val available = members.filter { it.online && it.id != self }.map { it.id }
        if (recipient.isNotBlank()) require(recipient in available) { "That member is offline. Reconnect before sending a direct message." }
        return (if (recipient.isBlank()) available else listOf(recipient)).also {
            require(it.isNotEmpty()) { "Wait for another person to join before sending." }
            require(it.size < MAX_MEMBERS && it.distinct().size == it.size && it.all(::identity))
        }
    }
    fun validateData(packet: LanPacket, expectedRoom: String, authenticatedSender: String, known: Set<String>) {
        require(packet.kind == LanKind.DATA && packet.roomId == expectedRoom && room(packet.roomId)) { "Wrong room or packet type" }
        require(identity(authenticatedSender) && packet.senderId == authenticatedSender) { "Sender identity does not match its authenticated connection" }
        require(authenticatedSender in known) { "Sender is not a member" }
        require(packet.password.isEmpty() && packet.membersCount == 0 && packet.error.isEmpty()) { "Unexpected control data in a message" }
        require(packet.targetsCount in 1 until MAX_MEMBERS && packet.targetsList.distinct().size == packet.targetsCount)
        require(packet.targetsList.all { identity(it) && it in known && it != authenticatedSender }) { "Invalid message audience" }
        val message = Wire.decode(packet.envelope.toByteArray())
        require(message.type == MessageType.TEXT_FINAL || message.type == MessageType.ALERT)
        val chat = !message.body.isEmpty && TextOptionsBody.parseFrom(message.body).silentChat
        require(!chat || message.type != MessageType.ALERT) { "Text chats cannot trigger alerts" }
        if (packet.recipientId.isNotBlank()) {
            require(chat && packet.targetsList == listOf(packet.recipientId)) { "Direct messages must be silent and have exactly one recipient" }
        }
    }
    fun sameMessage(first: LanPacket, next: LanPacket): Boolean =
        first.roomId == next.roomId && first.senderId == next.senderId && first.recipientId == next.recipientId &&
            first.targetsList == next.targetsList && first.envelope == next.envelope

    fun ipv4(value: String): Long? {
        if (!value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null
        val parts = value.split('.').map { it.toInt() }
        if (parts.any { it !in 0..255 }) return null
        return parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    }
    fun privateAddress(value: String): Boolean {
        val ip = ipv4(value) ?: return false
        return ip ushr 24 == 10L || ip ushr 20 == 0xac1L || ip ushr 16 == 0xc0a8L || ip ushr 16 == 0xa9feL
    }
    fun onLink(address: String, local: String, prefix: Int): Boolean {
        if (!privateAddress(address) || !privateAddress(local) || prefix !in 1..32) return false
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return (ipv4(address)!! and mask) == (ipv4(local)!! and mask)
    }
}

object LanWire {
    fun encode(packet: LanPacket): ByteArray {
        val clean = packet.toBuilder().setVersion(LanRules.VERSION).setCrc32(0).build()
        val crc = CRC32().apply { update(clean.toByteArray()) }.value.toInt()
        return clean.toBuilder().setCrc32(crc).build().toByteArray().also { require(it.size in 1..Wire.MAX_FRAME) }
    }
    fun decode(bytes: ByteArray): LanPacket {
        require(bytes.size in 1..Wire.MAX_FRAME)
        val packet = LanPacket.parseFrom(bytes)
        require(packet.version == LanRules.VERSION) { "Update iTantra on both phones: incompatible local-network protocol." }
        require(packet.kind != LanKind.LAN_UNSPECIFIED && packet.kind != LanKind.UNRECOGNIZED)
        require(packet.crc32 == CRC32().apply { update(packet.toBuilder().setCrc32(0).build().toByteArray()) }.value.toInt())
        require(packet.membersCount <= 256 && packet.targetsCount < LanRules.MAX_MEMBERS)
        require(packet.password.length <= 64 && packet.error.length <= 512)
        return packet
    }
}

data class AdmissionSecret(val salt: ByteArray, val verifier: ByteArray) {
    fun matches(password: String): Boolean = password.length in 8..64 && MessageDigest.isEqual(verifier, derive(password, salt))
    companion object {
        fun create(password: String): AdmissionSecret {
            require(password.length in 8..64) { "Use a group password with 8–64 characters." }
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            return AdmissionSecret(salt, derive(password, salt))
        }
        private fun derive(password: String, salt: ByteArray): ByteArray {
            val chars = password.toCharArray()
            val spec = PBEKeySpec(chars, salt, 120_000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { chars.fill('\u0000'); spec.clearPassword() }
        }
    }
}
