package org.itantra.app.core

/** What kind of conversation a thread represents. */
enum class ThreadKind { DIRECT, GROUP, MEMBER }

/**
 * One row in the Messages list.
 *
 * [id] is stable across disconnects, which is what lets a conversation resume:
 * reconnecting to the same peer or rejoining the same room lands on the same
 * thread with its existing history rather than starting over.
 */
data class ConversationThread(
    val id: String,
    val kind: ThreadKind,
    val title: String,
    val peerId: String,
    val roomId: String,
    val memberId: String,
    val lastText: String,
    val lastAtMs: Long,
    val lastWasVoice: Boolean,
    val lastOutgoing: Boolean,
    val unread: Int,
    val transport: String
)

/**
 * A minimal view of a stored message, so thread grouping can be unit tested
 * without Room or Android.
 */
data class ThreadMessage(
    val peerId: String,
    val peerName: String,
    val roomId: String,
    val roomName: String,
    val senderId: String,
    val targetId: String,
    val direction: String,
    val channel: String,
    val text: String,
    val createdAtMs: Long,
    val readLocally: Boolean,
    val transport: String
)

object Conversations {

    /**
     * Groups stored messages into threads, newest first.
     *
     * Voice and text share a thread because they are the same conversation with
     * the same person; only text is unread-tracked, since voice is spoken on
     * arrival. Relay traffic is excluded: those alerts have no reply thread and
     * belong to Emergency SOS history.
     */
    fun threads(messages: List<ThreadMessage>, selfId: String): List<ConversationThread> {
        val grouped = LinkedHashMap<String, MutableList<ThreadMessage>>()
        for (message in messages) {
            if (message.text.isBlank()) continue
            if (message.roomId.startsWith(RELAY_PREFIX)) continue
            val id = threadId(message, selfId) ?: continue
            grouped.getOrPut(id) { mutableListOf() }.add(message)
        }
        return grouped.map { (id, items) ->
            val newest = items.maxByOrNull { it.createdAtMs } ?: items.first()
            val unread = items.count {
                it.channel == CHAT && it.direction == INCOMING && !it.readLocally
            }
            ConversationThread(
                id = id,
                kind = kindOf(newest, selfId),
                title = titleOf(newest, selfId),
                peerId = newest.peerId,
                roomId = newest.roomId,
                memberId = memberOf(newest, selfId),
                lastText = newest.text,
                lastAtMs = newest.createdAtMs,
                lastWasVoice = newest.channel != CHAT,
                lastOutgoing = newest.direction == OUTGOING,
                unread = unread,
                transport = newest.transport
            )
        }.sortedByDescending { it.lastAtMs }
    }

    /** Stable identity for the thread a message belongs to, or null to skip it. */
    private fun threadId(message: ThreadMessage, selfId: String): String? {
        if (message.roomId.isBlank()) {
            return if (message.peerId.isBlank()) null else "peer:${message.peerId}"
        }
        val member = memberOf(message, selfId)
        return if (member.isBlank()) "room:${message.roomId}"
        else "member:${message.roomId}:$member"
    }

    /** The other participant of a private room message, blank for group-wide ones. */
    private fun memberOf(message: ThreadMessage, selfId: String): String {
        if (message.roomId.isBlank() || message.targetId.isBlank()) return ""
        return if (message.direction == OUTGOING) message.targetId
        else if (message.targetId == selfId) message.senderId else ""
    }

    private fun kindOf(message: ThreadMessage, selfId: String): ThreadKind = when {
        message.roomId.isBlank() -> ThreadKind.DIRECT
        memberOf(message, selfId).isNotBlank() -> ThreadKind.MEMBER
        else -> ThreadKind.GROUP
    }

    private fun titleOf(message: ThreadMessage, selfId: String): String = when (kindOf(message, selfId)) {
        // A private room message names the other person, not the room.
        ThreadKind.MEMBER -> message.peerName.ifBlank { "Group member" }
        ThreadKind.GROUP -> message.roomName.ifBlank { "Group" }
        ThreadKind.DIRECT -> message.peerName.ifBlank { "Nearby phone" }
    }

    /** Short relative age, for example "2 min ago". Never invents a future time. */
    fun relativeTime(atMs: Long, nowMs: Long): String {
        val elapsed = (nowMs - atMs).coerceAtLeast(0)
        val minutes = elapsed / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            elapsed < 60_000 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
            days < 7 -> if (days == 1L) "yesterday" else "$days days ago"
            else -> "${days / 7} wk ago"
        }
    }

    private const val CHAT = "CHAT"
    private const val INCOMING = "IN"
    private const val OUTGOING = "OUT"

    /** Relay alerts live in their own namespaces and are not reply threads. */
    private const val RELAY_PREFIX = "ble:"
}
