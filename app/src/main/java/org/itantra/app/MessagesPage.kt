package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.core.ConversationThread
import org.itantra.app.core.Conversations
import org.itantra.app.core.ThreadKind
import org.itantra.app.core.ThreadMessage
import org.itantra.app.data.MessageEntity

private fun MessageEntity.toThreadMessage() = ThreadMessage(
    peerId = peerId, peerName = peerName, roomId = roomId, roomName = roomName,
    senderId = senderId, targetId = targetId, direction = direction, channel = channel,
    text = text, createdAtMs = createdAtMs, readLocally = readLocally, transport = transport
)

/**
 * Messages starts at a list of conversations rather than whichever peer happens to
 * be connected. That is what makes a chat resumable: the thread is keyed by peer
 * or room, so reconnecting later reopens the same history.
 */
@Composable internal fun MessagesPage(app: AppRuntime, onDevices: () -> Unit) {
    val all by app.store.messages.collectAsStateWithLifecycle()
    val room by app.lan.state.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val mode by app.mode.collectAsStateWithLifecycle()
    var openThread by rememberSaveable { mutableStateOf<String?>(null) }

    val stored = remember(all, room.selfId) {
        Conversations.threads(all.map { it.toThreadMessage() }, room.selfId)
    }
    // A brand new connection has no messages yet, so give it a row to tap.
    val threads = remember(stored, room.roomId, room.active, session.ready, session.peerId) {
        val liveId = when {
            mode.isLan && room.active && room.roomId.isNotBlank() -> "room:${room.roomId}"
            !mode.isLan && session.ready && session.peerId.isNotBlank() -> "peer:${session.peerId}"
            else -> null
        }
        if (liveId == null || stored.any { it.id == liveId }) stored
        else listOf(
            ConversationThread(
                id = liveId,
                kind = if (mode.isLan && room.group) ThreadKind.GROUP else ThreadKind.DIRECT,
                title = if (mode.isLan) room.name.ifBlank { room.title } else session.peerName,
                peerId = if (mode.isLan) "" else session.peerId,
                roomId = if (mode.isLan) room.roomId else "",
                memberId = "",
                lastText = "No messages yet",
                lastAtMs = System.currentTimeMillis(),
                lastWasVoice = false,
                lastOutgoing = false,
                unread = 0,
                transport = mode.label
            )
        ) + stored
    }
    val selected = threads.find { it.id == openThread }

    // An open thread that is also the live conversation should keep working even
    // if its last message scrolls out of the derived list.
    if (openThread != null && selected == null) {
        LaunchedEffect(openThread) { openThread = null }
    }

    when {
        selected == null -> ConversationList(app, threads, session.ready, session.peerId,
            room.roomId, onDevices) { openThread = it }
        selected.kind == ThreadKind.DIRECT ->
            ChatPage(app, onDevices, peerId = selected.peerId, title = selected.title) { openThread = null }
        else -> LanMessagesPage(app, onDevices, initialDirect = selected.kind == ThreadKind.MEMBER,
            initialRecipient = selected.memberId) { openThread = null }
    }
}

@Composable private fun ConversationList(
    app: AppRuntime,
    threads: List<ConversationThread>,
    connected: Boolean,
    connectedPeerId: String,
    connectedRoomId: String,
    onDevices: () -> Unit,
    onOpen: (String) -> Unit
) {
    val now = remember(threads) { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Messages", "Silent text. Voice stays on Talk.") }
        if (threads.isEmpty()) item {
            EmptyCard("No conversations yet",
                if (connected) "Say hello to start. Your history stays here for next time."
                else "Connect a nearby phone to start a conversation.")
            Spacer(Modifier.height(8.dp))
            Button(onDevices, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = AppDesign.Control) {
                Text(if (connected) "Open a conversation" else "Find nearby devices")
            }
        }
        items(threads, key = { it.id }) { thread ->
            val live = connected && when (thread.kind) {
                ThreadKind.DIRECT -> thread.peerId == connectedPeerId
                else -> thread.roomId == connectedRoomId && connectedRoomId.isNotBlank()
            }
            ThreadRow(thread, live, now) { onOpen(thread.id) }
        }
        if (threads.isNotEmpty()) item {
            Hint("Tap a conversation to continue it. History is kept on this phone only.")
        }
    }
}

@Composable private fun ThreadRow(
    thread: ConversationThread,
    live: Boolean,
    nowMs: Long,
    onOpen: () -> Unit
) {
    Card(onClick = onOpen, Modifier.fillMaxWidth(), shape = AppDesign.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (thread.unread > 0) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Icon says what kind of conversation this is; tint says whether it is live.
            IconBadge(
                when (thread.kind) {
                    ThreadKind.GROUP -> AppIcons.Connections
                    ThreadKind.MEMBER -> AppIcons.Messages
                    ThreadKind.DIRECT -> if (thread.lastWasVoice) AppIcons.Speaker else AppIcons.Messages
                },
                highlighted = live || thread.unread > 0,
                size = 42.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(thread.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (thread.kind == ThreadKind.GROUP) {
                        Spacer(Modifier.width(6.dp)); StatusTag("Group")
                    }
                    if (thread.kind == ThreadKind.MEMBER) {
                        Spacer(Modifier.width(6.dp)); StatusTag("Private")
                    }
                }
                Text(
                    (if (thread.lastOutgoing) "You: " else "") +
                        (if (thread.lastWasVoice) "\uD83C\uDF99 " else "") + thread.lastText,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Hint(
                    if (live) "Connected now · " + Conversations.relativeTime(thread.lastAtMs, nowMs)
                    else "Last connected " + Conversations.relativeTime(thread.lastAtMs, nowMs) +
                        " · " + thread.transport
                )
            }
            // Scales in so a newly arrived message draws the eye without a jump.
            androidx.compose.animation.AnimatedVisibility(
                visible = thread.unread > 0,
                enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()
            ) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Text(thread.unread.toString()) }
            }
        }
    }
}

/** Shared back row for an opened conversation. */
@Composable internal fun ThreadHeader(title: String, detail: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to conversations") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Hint(detail)
        }
    }
}
