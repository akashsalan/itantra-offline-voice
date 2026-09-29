package org.itantra.app.core

import java.io.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class RelayKind { SOS, DELIVERED, PLAYED, ACKNOWLEDGED }
data class RelayMessage(val id: String, val kind: RelayKind, val reference: String, val recipient: String,
    val language: String, val text: String, val publicKey: ByteArray, val signature: ByteArray) {
    val sender: String get() = RelayPacket.hex(MessageDigest.getInstance("SHA-256").digest(publicKey))
    val key: String get() = sender + ":" + id
}
data class RelayPacket(val message: RelayMessage, val hops: Int, val remainingMs: Long) {
    companion object {
        const val MAX_BYTES = 2048
        const val MAX_TEXT_BYTES = 1024
        const val MAX_HOPS = 3
        const val TTL_MS = 300_000L
        private val aad = "iTantra trusted-team emergency relay v1".toByteArray()
        private val random = SecureRandom()
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun teamKey(code: String): ByteArray {
            val value = code.filterNot { it.isWhitespace() || it == '-' }.lowercase()
            require(value.matches(Regex("[a-f0-9]{64}"))) { "Enter the complete 64-character team code." }
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        fun newCode() = hex(ByteArray(32).also(random::nextBytes))
        fun teamId(key: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(aad + key)).take(16)
        private fun unsigned(m: RelayMessage): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1); out.writeUTF(m.id); out.writeByte(m.kind.ordinal); out.writeUTF(m.reference)
                out.writeUTF(m.recipient); out.writeUTF(m.language); out.writeUTF(m.text)
                out.writeInt(m.publicKey.size); out.write(m.publicKey)
            }
        }.toByteArray()
        private fun validate(m: RelayMessage) {
            require(m.id.matches(Regex("[0-7][a-f0-9]{31}")) && m.id.take(16).toLong(16) > 0 && m.publicKey.size in 64..160 && m.signature.size in 64..80)
            require(m.language in LanguageCode.entries.map { it.code })
            if (m.kind == RelayKind.SOS) {
                require(m.reference.isBlank() && m.recipient.isBlank() && m.text.any { it.isLetterOrDigit() })
                require(m.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "BLE emergency text is limited to 1024 UTF-8 bytes. Shorten this alert." }
            } else require(m.reference.matches(Regex("[a-f0-9]{32}")) && m.recipient.matches(Regex("[a-f0-9]{64}")) && m.text.isBlank())
        }
        fun create(kind: RelayKind, language: String, text: String, publicKey: ByteArray,
            reference: String = "", recipient: String = "", id: String = hex(ByteArray(16).also { random.nextBytes(it); it[0] = (it[0].toInt() and 127).toByte() }),
            sign: (ByteArray) -> ByteArray): RelayMessage {
            val draft = RelayMessage(id, kind, reference, recipient,
                language, text, publicKey, ByteArray(0))
            return draft.copy(signature = sign(unsigned(draft))).also(::validate)
        }
        fun seal(packet: RelayPacket, key: ByteArray): ByteArray {
            validate(packet.message)
            require(packet.hops in 1..MAX_HOPS && packet.remainingMs in 1..TTL_MS && key.size == 32)
            val body = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
                it.writeByte(packet.hops); it.writeLong(packet.remainingMs)
                val data = unsigned(packet.message)
                it.writeInt(data.size); it.write(data); it.writeInt(packet.message.signature.size); it.write(packet.message.signature)
            } }.toByteArray()
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            return (nonce + cipher.doFinal(body)).also { require(it.size <= MAX_BYTES) }
        }
        fun open(frame: ByteArray, key: ByteArray): RelayPacket {
            require(frame.size in 64..MAX_BYTES && key.size == 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, frame.copyOfRange(0, 12))); cipher.updateAAD(aad)
            DataInputStream(ByteArrayInputStream(cipher.doFinal(frame, 12, frame.size - 12))).use { input ->
                val hops = input.readUnsignedByte(); val remaining = input.readLong()
                require(hops in 1..MAX_HOPS && remaining in 1..TTL_MS)
                val data = input.readBounded(MAX_BYTES)
                val signature = input.readBounded(80)
                require(input.available() == 0)
                val message = DataInputStream(ByteArrayInputStream(data)).use { body ->
                    require(body.readInt() == 1)
                    val id = body.readUTF(); val kind = RelayKind.entries[body.readUnsignedByte()]
                    val ref = body.readUTF(); val recipient = body.readUTF(); val language = body.readUTF(); val text = body.readUTF()
                    val publicKey = body.readBounded(160); require(body.available() == 0)
                    RelayMessage(id, kind, ref, recipient, language, text, publicKey, signature).also(::validate)
                }
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(message.publicKey))
                require((publicKey as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256)
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey); verifier.update(data); require(verifier.verify(signature))
                return RelayPacket(message, hops, remaining)
            }
        }
        private fun DataInputStream.readBounded(max: Int): ByteArray {
            val size = readInt(); require(size in 1..max && size <= available())
            return ByteArray(size).also(::readFully)
        }
    }
}

/** Serialized GATT writes also work at the mandatory 23-byte MTU. No assumed 517-byte MTU. */
object RelayFragments {
    private const val HEADER = 6
    fun split(frame: ByteArray, mtu: Int, transfer: Int): List<ByteArray> {
        require(frame.size in 1..RelayPacket.MAX_BYTES)
        val payload = (mtu - 3).coerceIn(20, 512) - HEADER
        val count = (frame.size + payload - 1) / payload
        return (0 until count).map { index ->
            java.nio.ByteBuffer.allocate(HEADER + minOf(payload, frame.size - index * payload))
                .putShort(transfer.toShort()).putShort(index.toShort()).putShort(count.toShort())
                .put(frame, index * payload, minOf(payload, frame.size - index * payload)).array()
        }
    }
    class Assembler {
        private var transfer = -1; private var expected = 0; private var total = 0; private var deadline = 0L
        private val buffer = ByteArrayOutputStream()
        var framedBytes: Int = 0
            private set
        @Synchronized fun accept(chunk: ByteArray, now: Long): ByteArray? {
            require(chunk.size in 7..512)
            val input = java.nio.ByteBuffer.wrap(chunk)
            val id = input.short.toInt() and 65535; val index = input.short.toInt() and 65535; val count = input.short.toInt() and 65535
            require(count in 1..147 && index < count)
            if (index == 0) { transfer = id; expected = 0; total = count; buffer.reset(); framedBytes = 0; deadline = now + 15000 }
            require(id == transfer && index == expected && count == total && now <= deadline)
            require(buffer.size() + input.remaining() <= RelayPacket.MAX_BYTES)
            buffer.write(chunk, HEADER, chunk.size - HEADER); framedBytes += chunk.size; expected++
            return if (expected == total) buffer.toByteArray().also { transfer = -1; buffer.reset() } else null
        }
    }
}
