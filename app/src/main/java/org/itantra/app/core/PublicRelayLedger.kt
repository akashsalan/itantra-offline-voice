package org.itantra.app.core

data class PublicRelayPending(val signed: PublicRelayMessage, val hops: Int, val deadline: Long,
    val attempts: MutableMap<String, Pair<Int, Long>> = linkedMapOf(), val sentTo: MutableSet<String> = linkedSetOf())

/** Main-thread owned. Acceptance automatically makes a valid message eligible for forwarding.
 * Hop zero is the sender; A -> B -> C -> D consumes all three radio hops.
 * Limits mitigate accidental floods, not coordinated attackers rotating identities. */
class PublicRelayLedger {
    val pending = linkedMapOf<String, PublicRelayPending>()
    private val seen = linkedMapOf<String, Long>()
    private val receipts = linkedMapOf<String, Long>()
    private val recent = ArrayDeque<Triple<String, Boolean, Long>>()
    fun accept(signed: PublicRelayMessage, hops: Int, remaining: Long, now: Long, wallNow: Long): Boolean {
        expire(now)
        if (hops !in 0..3 || remaining !in 1..RelayPacket.TTL_MS || signed.expiresAtMs <= wallNow ||
            signed.createdAtMs > wallNow + PublicRelayPacket.FUTURE_SKEW_MS || signed.key in seen) return false
        val message = signed.message
        val sos = message.kind == RelayKind.SOS
        val semantic = "${message.sender}:${message.kind}:${message.recipient}:${message.reference}"
        if (!sos && semantic in receipts) return false
        // Reserve eight queue slots for locally created alerts/receipts.
        if (pending.size >= (if (hops == 0) 128 else 120) || seen.size >= 1024 || receipts.size >= 1024) return false
        val local = hops == 0
        if (sos && recent.count { it.first == message.sender && it.second } >= 2) return false
        if (!local && (recent.count { it.second } >= 12 || recent.size >= 120)) return false
        val budget = minOf(remaining, signed.expiresAtMs - wallNow, RelayPacket.TTL_MS)
        pending[signed.key] = PublicRelayPending(signed, hops, now + budget)
        // Keep duplicates suppressed for the whole possible signed lifetime, not just transit budget.
        seen[signed.key] = now + RelayPacket.TTL_MS + PublicRelayPacket.FUTURE_SKEW_MS
        if (!sos) receipts[semantic] = now + RelayPacket.TTL_MS + PublicRelayPacket.FUTURE_SKEW_MS
        recent.addLast(Triple(message.sender, sos, now))
        return true
    }
    fun expire(now: Long): List<PublicRelayPending> {
        seen.entries.removeAll { it.value <= now }; receipts.entries.removeAll { it.value <= now }
        while (recent.isNotEmpty() && now - recent.first().third >= 60_000) recent.removeFirst()
        return pending.values.filter { it.deadline <= now }.also { entries -> entries.forEach { pending.remove(it.signed.key) } }
    }
    fun candidates(peer: String, now: Long, wallNow: Long) = pending.values.filter {
        val attempt = it.attempts[peer]
        it.hops < RelayPacket.MAX_HOPS && it.deadline - now > 30_000 && it.signed.expiresAtMs - wallNow > 30_000 && peer !in it.sentTo &&
            (attempt == null || (attempt.first < 3 && now - attempt.second >= 30_000)) && (peer in it.attempts || it.attempts.size < 32)
    }.sortedBy { if (it.signed.message.kind == RelayKind.SOS) 0 else 1 }.take(4)
    fun attempting(key: String, peer: String, now: Long) {
        pending[key]?.attempts?.let { it[peer] = ((it[peer]?.first ?: 0) + 1) to now }
    }
    fun sent(key: String, peer: String) { pending[key]?.sentTo?.add(peer) }
    fun stop() { pending.clear() } // Consent never resumes old forwarding; retain recent dedup/rate limits.
}
