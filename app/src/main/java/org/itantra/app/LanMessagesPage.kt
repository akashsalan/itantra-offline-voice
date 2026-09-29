package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.data.MessageEntity
import org.itantra.app.lan.LanRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun deliveryLabel(message: MessageEntity): String {
    if (org.itantra.app.relay.PublicRelaySession.owns(message)) return "Public BLE · " + message.delivery.lowercase().replace('_', ' ') + " · recipients unknown"
    if (org.itantra.app.relay.RelaySession.owns(message)) return "BLE · " + message.delivery.lowercase().replace('_', ' ') + " · team total unknown"
    if (message.roomId.isBlank()) return message.delivery.lowercase().replaceFirstChar { it.uppercase() }
    val total = LanRules.ids(message.targets).size
    val delivered = LanRules.ids(message.deliveredTo).size
    val played = LanRules.ids(message.playedBy).size
    val acknowledged = LanRules.ids(message.acknowledgedBy).size
    return buildString {
        append("Delivered $delivered/$total")
        if (message.channel == "VOICE") append(" · played $played/$total")
        if (message.channel == "VOICE" || acknowledged > 0) append(" · acknowledged $acknowledged/$total")
        if (message.delivery == "FAILED") append(" · retry needed")
        else if (delivered < total) append(" · waiting")
    }
}

@Composable internal fun LanMessagesPage(
    app: AppRuntime,
    onDevices: () -> Unit,
    initialDirect: Boolean = false,
    initialRecipient: String = "",
    onBack: (() -> Unit)? = null
) {
    val room by app.lan.state.collectAsStateWithLifecycle()
    val all by app.store.messages.collectAsStateWithLifecycle()
    // Opening a thread from Messages lands directly in that conversation.
    var direct by rememberSaveable(room.roomId, initialDirect) { mutableStateOf(initialDirect) }
    var recipient by rememberSaveable(room.roomId, initialRecipient) { mutableStateOf(initialRecipient) }
    val drafts = rememberSaveable(room.roomId, saver = mapSaver(
        save = { it.toMap() }, restore = { values -> values.map { it.key to (it.value as String) }.toMutableStateMap() }
    )) { mutableStateMapOf<String, String>() }
    var sending by remember { mutableStateOf(false) }
    val people = room.members.filter { it.id != room.selfId }.sortedWith(compareByDescending<org.itantra.protocol.lan.LanMember> { it.online }.thenBy { it.name })
    val person = people.find { it.id == recipient }
    val picker = room.group && direct && recipient.isBlank()
    val target = if (room.group && direct) recipient else ""
    val thread = if (room.group && direct) "dm:$recipient" else "group"
    val draft = drafts[thread] ?: ""
    val history = all.filter { it.roomId == room.roomId && it.roomId.isNotBlank() && it.channel == "CHAT" }
    val chats = history.filter {
        if (target.isBlank()) it.targetId.isBlank()
        else (it.direction == "OUT" && it.targetId == target) || (it.direction == "IN" && it.senderId == target && it.targetId == room.selfId)
    }
    val unread = history.filter { it.direction == "IN" && !it.readLocally }
    val visibleUnread = if (picker) emptyList() else chats.filter { it.direction == "IN" && !it.readLocally }.map { it.key }
    LaunchedEffect(visibleUnread) { if (visibleUnread.isNotEmpty()) app.lan.markRead(visibleUnread) }
    val scroll = rememberLazyListState()
    LaunchedEffect(thread, chats.firstOrNull()?.key) { if (chats.isNotEmpty() && !picker) scroll.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onBack != null) ThreadHeader(
            if (target.isNotBlank()) person?.name ?: "Private" else room.name.ifBlank { room.title },
            if (room.ready) "Connected now" else "Not connected · draft is kept", onBack
        ) else LanConnectionCard(app, onDevices)
        if (room.group && onBack == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val groupUnread = unread.count { it.targetId.isBlank() }
            val directUnread = unread.count { it.targetId == room.selfId }
            FilterChip(!direct, { direct = false }, { Text("Group chat" + if (groupUnread > 0) " ($groupUnread)" else "") }, Modifier.weight(1f))
            FilterChip(direct, { direct = true; recipient = "" }, { Text("Private" + if (directUnread > 0) " ($directUnread)" else "") }, Modifier.weight(1f))
        }
        if (picker) {
            Text("Choose a person", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Hint("Only the selected member receives this conversation. The group creator forwards private messages and can read them.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(people, key = { it.id }) { member ->
                    val count = unread.count { it.senderId == member.id && it.targetId == room.selfId }
                    Card(onClick = { recipient = member.id }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(member.name, fontWeight = FontWeight.SemiBold)
                                Hint((if (member.online) "Connected" else "Offline · view history") + if (member.id == room.hostId) " · creator" else "")
                            }
                            if (count > 0) Badge(containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary) { Text(count.toString()) }
                            Text("  Open", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (people.isEmpty()) item { EmptyCard("No other members yet", "People appear here after they join this group.") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (target.isNotBlank()) "Private to " + (person?.name ?: "selected member") else if (room.group) "Group: ${room.name}" else "Message " + room.title,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Hint(if (target.isNotBlank()) "Private text · Talk still speaks to the group" else "Silent text · no microphone or automatic speech")
                }
                if (target.isNotBlank()) TextButton({ recipient = "" }) { Text("People") }
            }
            if (chats.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyCard("Start this conversation", if (target.isBlank()) "Your text goes to everyone currently connected. Voice messages stay on Talk."
                    else "Your text goes only to the selected person. New group members do not receive earlier messages.")
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(chats, key = { it.key }) { message ->
                    val outgoing = message.direction == "OUT"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                        Card(Modifier.fillMaxWidth(.9f), shape = if (outgoing) AppDesign.OutgoingBubble else AppDesign.IncomingBubble,
                            colors = CardDefaults.cardColors(containerColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (outgoing) "You" else message.peerName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(message.text)
                                Hint(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAtMs)) +
                                    if (outgoing) " · " + deliveryLabel(message) else "")
                                if (outgoing && message.delivery == "FAILED") TextButton({ app.retry(message.key) }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
            val canSend = room.ready && (target.isBlank() || person?.online == true)
            if (!canSend) Hint(if (target.isNotBlank() && person?.online != true) "This person is offline. Your draft stays here."
                else "Connect with someone to send. Your draft stays here.")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { drafts[thread] = it.take(8000) }, Modifier.weight(1f), enabled = !sending, maxLines = 4,
                    placeholder = { Text(if (target.isNotBlank()) "Private to " + (person?.name ?: "member") else "Message " + room.title) }, shape = AppDesign.Control)
                Button({
                    val sentThread = thread
                    val sentText = draft
                    sending = true
                    app.sendChat(sentText, target) { success ->
                        if (success && drafts[sentThread] == sentText) drafts[sentThread] = ""
                        sending = false
                    }
                }, enabled = canSend && draft.isNotBlank() && !sending, modifier = Modifier.heightIn(min = 56.dp), shape = AppDesign.Control) { Text(if (sending) "…" else "Send") }
            }
        }
    }
}
