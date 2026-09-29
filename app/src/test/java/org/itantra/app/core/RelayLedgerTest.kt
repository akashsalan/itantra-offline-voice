package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class RelayLedgerTest {
    private val message = RelayMessage("0".repeat(15) + "1" + "a".repeat(16), RelayKind.SOS, "", "", "en", "Help", byteArrayOf(1), byteArrayOf(2))
    @Test fun duplicateDoesNotExtendExpiryOrReenqueue() {
        val queue = RelayLedger()
        assertTrue(queue.accept(message, 1, 60000, 0))
        assertFalse(queue.accept(message, 1, 60000, 50000))
        assertEquals(60000L, queue.pending.values.single().deadline)
        queue.expire(60000); assertTrue(queue.pending.isEmpty())
        assertFalse(queue.accept(message, 1, 60000, 60001))
    }
    @Test fun hopLimitAndRetriesAreBounded() {
        val queue = RelayLedger(); queue.accept(message, 3, 300000, 0)
        assertTrue(queue.candidates("peer", 0).isEmpty())
        val fresh = RelayLedger(); fresh.accept(message, 0, 300000, 0)
        repeat(4) { fresh.attempting(message.key, "peer", it * 30000L) }
        assertTrue(fresh.candidates("peer", 120000).isEmpty())
    }
    @Test fun sequenceWindowRejectsReplayAfterIdCacheEviction() {
        val queue = RelayLedger(); queue.accept(message, 1, 60000, 0)
        queue.seen.clear(); queue.pending.clear()
        assertFalse(queue.accept(message, 1, 60000, 70000))
        val later = message.copy(id = "0".repeat(15) + "3" + "a".repeat(16))
        val reordered = message.copy(id = "0".repeat(15) + "2" + "a".repeat(16))
        assertTrue(queue.accept(later, 1, 60000, 70000)); assertTrue(queue.accept(reordered, 1, 60000, 70000))
    }
}
