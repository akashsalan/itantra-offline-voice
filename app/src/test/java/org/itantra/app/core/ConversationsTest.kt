package org.itantra.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {

    private val self = "me"

    private fun message(
        peerId: String = "p1",
        peerName: String = "Asha",
        roomId: String = "",
        roomName: String = "",
        senderId: String = "",
        targetId: String = "",
        direction: String = "IN",
        channel: String = "CHAT",
        text: String = "hello",
        at: Long = 1_000,
        read: Boolean = true,
        transport: String = "Wi-Fi Direct"
    ) = ThreadMessage(peerId, peerName, roomId, roomName, senderId, targetId,
        direction, channel, text, at, read, transport)

    @Test fun `one thread per peer regardless of how many messages`() {
        val threads = Conversations.threads(
            listOf(
                message(text = "first", at = 1),
                message(text = "second", at = 2),
                message(text = "third", at = 3)
            ), self
        )
        assertEquals(1, threads.size)
        assertEquals("third", threads.single().lastText)
        assertEquals(ThreadKind.DIRECT, threads.single().kind)
    }

    @Test fun `separate peers get separate threads, newest first`() {
        val threads = Conversations.threads(
            listOf(
                message(peerId = "p1", peerName = "Asha", at = 10),
                message(peerId = "p2", peerName = "Bala", at = 50)
            ), self
        )
        assertEquals(listOf("Bala", "Asha"), threads.map { it.title })
    }

    @Test fun `a past conversation survives connecting to someone else`() {
        // The defect this replaces: history was filtered to the current peer, so
        // an earlier chat became unreachable and could not be resumed.
        val threads = Conversations.threads(
            listOf(
                message(peerId = "old", peerName = "Earlier phone", at = 1),
                message(peerId = "new", peerName = "Current phone", at = 2)
            ), self
        )
        assertEquals(2, threads.size)
        assertTrue(threads.any { it.peerId == "old" })
    }

    @Test fun `reconnecting to the same peer reuses the same thread id`() {
        val before = Conversations.threads(listOf(message(at = 1)), self).single()
        val after = Conversations.threads(
            listOf(message(at = 1), message(text = "later", at = 900_000)), self
        ).single()
        assertEquals(before.id, after.id)
        assertEquals("later", after.lastText)
    }

    @Test fun `group messages and private messages are different threads`() {
        val threads = Conversations.threads(
            listOf(
                message(roomId = "r1", roomName = "Team", targetId = "", at = 5),
                message(roomId = "r1", roomName = "Team", senderId = "u2",
                    targetId = self, peerName = "Bala", at = 6)
            ), self
        )
        assertEquals(2, threads.size)
        assertEquals(ThreadKind.MEMBER, threads[0].kind)
        assertEquals("Bala", threads[0].title)
        assertEquals(ThreadKind.GROUP, threads[1].kind)
        assertEquals("Team", threads[1].title)
    }

    @Test fun `outgoing and incoming private messages share one member thread`() {
        val threads = Conversations.threads(
            listOf(
                message(roomId = "r1", direction = "OUT", targetId = "u2", at = 1),
                message(roomId = "r1", direction = "IN", senderId = "u2", targetId = self, at = 2)
            ), self
        )
        assertEquals(1, threads.size)
        assertEquals(ThreadKind.MEMBER, threads.single().kind)
    }

    @Test fun `a private message meant for someone else is not shown`() {
        // The host relays other people's DMs; those rows must not become threads.
        val threads = Conversations.threads(
            listOf(message(roomId = "r1", direction = "IN", senderId = "u2", targetId = "u3", at = 1)),
            self
        )
        assertEquals(ThreadKind.GROUP, threads.single().kind)
    }

    @Test fun `only unread incoming text counts as unread`() {
        val threads = Conversations.threads(
            listOf(
                message(direction = "IN", channel = "CHAT", read = false, at = 1),
                message(direction = "IN", channel = "CHAT", read = false, at = 2),
                message(direction = "IN", channel = "VOICE", read = false, at = 3),
                message(direction = "OUT", channel = "CHAT", read = false, at = 4)
            ), self
        )
        assertEquals(2, threads.single().unread)
    }

    @Test fun `voice and text share a thread and the newest wins the preview`() {
        val threads = Conversations.threads(
            listOf(
                message(channel = "CHAT", text = "typed", at = 1),
                message(channel = "VOICE", text = "spoken", at = 2)
            ), self
        )
        assertEquals("spoken", threads.single().lastText)
        assertTrue(threads.single().lastWasVoice)
    }

    @Test fun `relay alerts are excluded from the messages list`() {
        val threads = Conversations.threads(
            listOf(message(roomId = "ble:public", text = "SOS", at = 1)), self
        )
        assertTrue(threads.isEmpty())
    }

    @Test fun `blank text and peerless rows are skipped`() {
        val threads = Conversations.threads(
            listOf(message(text = "   ", at = 1), message(peerId = "", at = 2)), self
        )
        assertTrue(threads.isEmpty())
    }

    @Test fun `relative time reads naturally and never goes negative`() {
        val now = 10_000_000_000L
        assertEquals("just now", Conversations.relativeTime(now - 5_000, now))
        assertEquals("3 min ago", Conversations.relativeTime(now - 3 * 60_000, now))
        assertEquals("1 hour ago", Conversations.relativeTime(now - 3_600_000, now))
        assertEquals("5 hours ago", Conversations.relativeTime(now - 5 * 3_600_000, now))
        assertEquals("yesterday", Conversations.relativeTime(now - 25 * 3_600_000L, now))
        assertEquals("3 days ago", Conversations.relativeTime(now - 3 * 86_400_000L, now))
        assertEquals("2 wk ago", Conversations.relativeTime(now - 15 * 86_400_000L, now))
        // A clock change must not produce "in the future" text.
        assertEquals("just now", Conversations.relativeTime(now + 60_000, now))
    }
}
