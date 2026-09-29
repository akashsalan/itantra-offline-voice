package org.itantra.app.protocol

import org.itantra.app.core.LanguageCode
import org.itantra.app.core.UnicodeText
import org.itantra.protocol.v1.Envelope
import org.itantra.protocol.v1.MessageType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

object Wire {
    const val MAJOR = 1
    const val MINOR = 1
    const val MAX_FRAME = 64 * 1024
    const val MAX_TEXT = 2048
    const val PORT = 38773
    const val SERVICE = "_itantra._tcp"
    fun language(code: LanguageCode): org.itantra.protocol.v1.LanguageCode =
        org.itantra.protocol.v1.LanguageCode.valueOf(code.name)
    fun language(code: org.itantra.protocol.v1.LanguageCode): LanguageCode =
        LanguageCode.entries.singleOrNull { it.name == code.name }
            ?: throw IllegalArgumentException("Unsupported packet language")
    fun encode(builder: Envelope.Builder): ByteArray {
        val clean = builder.setCrc32(0).build()
        val bytes = clean.toByteArray()
        val result = clean.toBuilder().setCrc32(CRC32().apply { update(bytes) }.value.toInt()).build().toByteArray()
        require(result.size in 1..MAX_FRAME) { "Packet exceeds 64 KiB" }
        return result
    }
    fun decode(bytes: ByteArray): Envelope {
        require(bytes.size in 1..MAX_FRAME) { "Invalid frame size" }
        val packet = Envelope.parseFrom(bytes)
        require(packet.protocolMajor == MAJOR) { "Incompatible protocol major: ${packet.protocolMajor}" }
        require(packet.sessionId != 0L && packet.messageId != 0L) { "Missing packet identity" }
        require(packet.type != MessageType.UNRECOGNIZED && packet.type != MessageType.TYPE_UNSPECIFIED)
        val crc = CRC32().apply { update(packet.toBuilder().setCrc32(0).build().toByteArray()) }.value.toInt()
        require(crc == packet.crc32) { "Packet checksum mismatch" }
        if (packet.type in listOf(MessageType.TEXT_FINAL, MessageType.ALERT, MessageType.TEXT_PARTIAL)) {
            language(packet.language)
            require(packet.text.isNotBlank() && packet.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT)
            require(packet.text == UnicodeText.normalize(packet.text)) { "Non-canonical Unicode text" }
            require(packet.sequence > 0) { "Missing message sequence" }
        }
        return packet
    }
    fun read(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..MAX_FRAME) { "Invalid stream length: $size" }
        return ByteArray(size).also { stream.readFully(it) }
    }
    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_FRAME)
        DataOutputStream(output).apply { writeInt(payload.size); write(payload); flush() }
    }
    fun confirmation(firstId: String, firstNonce: ByteArray, secondId: String, secondNonce: ByteArray): String {
        val endpoints = listOf(firstId to firstNonce, secondId to secondNonce).sortedBy { it.first }
        val digest = MessageDigest.getInstance("SHA-256")
        endpoints.forEach { (id, nonce) ->
            digest.update(id.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()); digest.update(nonce)
        }
        // Human cross-check for a controlled WPA2 demo, not application authentication.
        return digest.digest().take(4).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    }
}
object DeliveryPolicy {
    const val MAX_SENDS = 4
    fun waitAfterAttempt(attempts: Int): Long = when (attempts) { 1 -> 1000; 2 -> 2000; else -> 4000 }
    fun advance(current: String, received: String): String {
        val ranks = mapOf("QUEUED" to 0, "SENDING" to 0, "FAILED" to 0, "DELIVERED" to 1, "PLAYED" to 2, "ACKNOWLEDGED" to 3)
        return if ((ranks[received] ?: -1) > (ranks[current] ?: -1)) received else current
    }
}
