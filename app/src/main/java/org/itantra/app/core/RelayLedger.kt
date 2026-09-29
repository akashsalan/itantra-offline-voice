package org.itantra.app.core

data class RelayPending(val message: RelayMessage, val hops: Int, val deadline: Long,
    val attempts: MutableMap<String, Pair<Int, Long>> = mutableMapOf())

/** Bounded flooding with a local elapsed-time budget. No cross-phone clock subtraction. */
class RelayLedger {
    data class Window(val highest: Long, val bitmap: Long)
    val pending = linkedMapOf<String, RelayPending>()
    val seen = linkedSetOf<String>()
    val windows = linkedMapOf<String, Window>()
    fun accept(message: RelayMessage, hops: Int, remaining: Long, now: Long): Boolean {
        expire(now)
        if (message.key in seen) return false
        val sequence = message.id.take(16).toLong(16)
        val previous = windows[message.sender]
        if (previous == null) require(windows.size < 128) { "This relay team has reached its device limit. Create a new trusted team." }
        val next = if (previous == null) Window(sequence, 1) else if (sequence > previous.highest) {
            val distance = sequence - previous.highest
            Window(sequence, (if (distance >= 64) 0 else previous.bitmap shl distance.toInt()) or 1)
        } else {
            val distance = previous.highest - sequence
            if (distance >= 64 || previous.bitmap and (1L shl distance.toInt()) != 0L) return false
            Window(previous.highest, previous.bitmap or (1L shl distance.toInt()))
        }
        require(hops in 0..RelayPacket.MAX_HOPS && remaining in 1..RelayPacket.TTL_MS)
        require(pending.size < 64) { "Emergency relay queue is full. Wait for expiry before retrying." }
        windows[message.sender] = next
        seen += message.key
        while (seen.size > 1024) seen.remove(seen.first())
        pending[message.key] = RelayPending(message, hops, now + remaining)
        return true
    }
    fun expire(now: Long): List<RelayPending> {
        val expired = pending.values.filter { it.deadline <= now }
        expired.forEach { pending.remove(it.message.key) }
        return expired
    }
    fun candidates(peer: String, now: Long): List<RelayPending> = pending.values.filter {
        val attempt = it.attempts[peer]
        it.hops < RelayPacket.MAX_HOPS && it.deadline - now > 30000 &&
            (attempt == null || (attempt.first < 4 && now - attempt.second >= 30000))
    }.sortedBy { if (it.message.kind == RelayKind.SOS) 0 else 1 }.take(4)
    fun attempting(id: String, peer: String, now: Long) {
        pending[id]?.attempts?.let { attempts ->
            if (attempts.size >= 32 && peer !in attempts) attempts.remove(attempts.keys.first())
            attempts[peer] = ((attempts[peer]?.first ?: 0) + 1) to now
        }
    }
}
