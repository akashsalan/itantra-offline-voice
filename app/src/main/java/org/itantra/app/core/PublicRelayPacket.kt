package org.itantra.app.core

import java.io.*
import java.security.*
import java.security.spec.X509EncodedKeySpec

/** Public, signed, NOT encrypted. Separate signing domain and wire format from private teams.
 * Device signatures detect tampering; they do not establish a person's identity or truth. */
data class PublicRelayMessage(val message: RelayMessage, val createdAtMs: Long) {
    val key get() = message.key
    val expiresAtMs get() = createdAtMs + RelayPacket.TTL_MS
}

data class PublicRelayPacket(val signed: PublicRelayMessage, val hops: Int, val remainingMs: Long) {
    companion object {
        private const val MAGIC = 0x49545031 // ITP1; never a trusted-team frame
        private const val DOMAIN = "iTantra public SOS v1"
        const val FUTURE_SKEW_MS = 120_000L
        private fun body(signed: PublicRelayMessage) = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                val m = signed.message
                out.writeUTF(DOMAIN); out.writeLong(signed.createdAtMs)
                out.writeUTF(m.id); out.writeByte(m.kind.ordinal); out.writeUTF(m.reference)
                out.writeUTF(m.recipient); out.writeUTF(m.language); out.writeUTF(m.text)
                out.writeInt(m.publicKey.size); out.write(m.publicKey)
            }
        }.toByteArray()
        private fun validate(signed: PublicRelayMessage) {
            val m = signed.message
            require(signed.createdAtMs in 1..(Long.MAX_VALUE - RelayPacket.TTL_MS))
            require(m.id.matches(Regex("[0-7][a-f0-9]{31}")) && m.id.take(16).toLong(16) > 0)
            require(m.publicKey.size in 64..160 && m.signature.size in 64..80)
            require(m.language in LanguageCode.entries.map { it.code })
            if (m.kind == RelayKind.SOS) {
                require(m.reference.isEmpty() && m.recipient.isEmpty() && m.text.any { it.isLetterOrDigit() })
                require(m.text.toByteArray(Charsets.UTF_8).size <= RelayPacket.MAX_TEXT_BYTES) { "Shorten the BLE alert to 1024 UTF-8 bytes." }
            } else require(m.text.isEmpty() && m.reference.matches(Regex("[0-7][a-f0-9]{31}")) && m.recipient.matches(Regex("[a-f0-9]{64}")))
        }
        fun create(kind: RelayKind, language: String, text: String, publicKey: ByteArray, id: String,
            createdAtMs: Long, reference: String = "", recipient: String = "", sign: (ByteArray) -> ByteArray): PublicRelayMessage {
            val draft = PublicRelayMessage(RelayMessage(id, kind, reference, recipient, language, text, publicKey, byteArrayOf()), createdAtMs)
            return draft.copy(message = draft.message.copy(signature = sign(body(draft)))).also(::validate)
        }
        fun encode(packet: PublicRelayPacket): ByteArray {
            validate(packet.signed)
            require(packet.hops in 1..RelayPacket.MAX_HOPS && packet.remainingMs in 1..RelayPacket.TTL_MS)
            return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC); out.writeByte(packet.hops); out.writeLong(packet.remainingMs)
                val body = body(packet.signed); out.writeInt(body.size); out.write(body)
                val signature = packet.signed.message.signature; out.writeInt(signature.size); out.write(signature)
            } }.toByteArray().also { require(it.size <= RelayPacket.MAX_BYTES) }
        }
        fun decode(frame: ByteArray, wallNow: Long): PublicRelayPacket {
            require(frame.size in 64..RelayPacket.MAX_BYTES)
            DataInputStream(ByteArrayInputStream(frame)).use { input ->
                require(input.readInt() == MAGIC)
                val hops = input.readUnsignedByte(); val remaining = input.readLong()
                require(hops in 1..RelayPacket.MAX_HOPS && remaining in 1..RelayPacket.TTL_MS)
                val bytes = input.bounded(RelayPacket.MAX_BYTES); val signature = input.bounded(80)
                require(input.available() == 0)
                val signed = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                    require(data.readUTF() == DOMAIN)
                    val created = data.readLong(); val id = data.readUTF()
                    val kind = RelayKind.entries[data.readUnsignedByte()]
                    val reference = data.readUTF(); val recipient = data.readUTF()
                    val language = data.readUTF(); val text = data.readUTF(); val publicKey = data.bounded(160)
                    require(data.available() == 0)
                    PublicRelayMessage(RelayMessage(id, kind, reference, recipient, language, text, publicKey, signature), created).also(::validate)
                }
                require(signed.createdAtMs <= wallNow + FUTURE_SKEW_MS && signed.expiresAtMs > wallNow) { "Public SOS expired or phone clocks differ." }
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signed.message.publicKey))
                require((key as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256)
                require(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(bytes); verify(signature) })
                return PublicRelayPacket(signed, hops, minOf(remaining, signed.expiresAtMs - wallNow))
            }
        }
        private fun DataInputStream.bounded(max: Int): ByteArray {
            val count = readInt(); require(count in 1..max && count <= available())
            return ByteArray(count).also(::readFully)
        }
    }
}
