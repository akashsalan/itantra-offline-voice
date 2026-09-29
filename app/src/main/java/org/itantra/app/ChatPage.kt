package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One direct conversation. [peerId] pins the thread so an earlier chat can be
 * reopened and continued after reconnecting, instead of only ever showing
 * whichever peer happens to be connected right now.
 */
@Composable internal fun ChatPage(
    app: AppRuntime,
    onDevices: () -> Unit,
    peerId: String? = null,
    title: String? = null,
    onBack: (() -> Unit)? = null
) {
    val session by app.radio.state.collectAsStateWithLifecycle()
    val link by app.link.collectAsStateWithLifecycle()
    val mode by app.transport.mode.collectAsStateWithLifecycle()
    val messages by app.store.messages.collectAsStateWithLifecycle()
    var draft by rememberSaveable(peerId ?: "") { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val thread = peerId ?: session.peerId.takeIf { it.isNotBlank() }
    val chats = messages.filter {
        it.roomId.isBlank() && it.channel == "CHAT" && (thread == null || it.peerId == thread)
    }
    val live = session.ready && (thread == null || session.peerId == thread)
    val name = title ?: chats.firstOrNull()?.peerName ?: session.peerName
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) ThreadHeader(name,
            if (live) "Connected now" else "Not connected · draft is kept", onBack)
        else {
            ConnectionCard(session.ready, session.peerName, link, mode.label, onDevices)
            Column {
                Text(if (session.ready) "Message " + session.peerName else "Messages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Hint("Text-only chat · no microphone or automatic speech")
            }
        }
        if (chats.isEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EmptyCard("A quiet way to stay in touch", "Type a message below. Voice conversations and playback controls are on Talk.")
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(chats, key = { it.key }) { message ->
                val outgoing = message.direction == "OUT"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 340.dp), shape = if (outgoing) AppDesign.OutgoingBubble else AppDesign.IncomingBubble,
                        colors = CardDefaults.cardColors(containerColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!outgoing) Text(message.peerName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(message.text)
                            Hint(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAtMs)) +
                                if (outgoing) " · " + message.delivery.lowercase() else "")
                            if (outgoing && message.delivery == "FAILED") TextButton({ app.retry(message.key) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
        if (!live) Hint(
            if (session.ready) "You are connected to someone else. Reconnect to $name to send."
            else "Connect a phone to send. Your draft stays here."
        )
        else if (!session.remoteChat) Hint("Update the other phone's iTantra app to enable text-only chat.")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it.take(8000) }, Modifier.weight(1f), enabled = !sending, maxLines = 4,
                placeholder = { Text(if (live) "Message $name" else "Type a message…") }, shape = AppDesign.Control)
            Button({
                val text = draft
                sending = true
                app.sendChat(text) { success -> if (success && draft == text) draft = ""; sending = false }
            }, enabled = live && session.remoteChat && draft.isNotBlank() && !sending,
                modifier = Modifier.heightIn(min = 56.dp), shape = AppDesign.Control,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) { Text(if (sending) "…" else "Send") }
        }
    }
}
