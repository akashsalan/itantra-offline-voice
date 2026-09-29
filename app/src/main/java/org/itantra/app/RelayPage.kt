package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.core.RelayPacket
import org.itantra.app.relay.RelaySession

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RelayPage(app: AppRuntime, start: () -> Unit, dismiss: () -> Unit, onSos: () -> Unit) {
    val relay by app.relay.state.collectAsStateWithLifecycle()
    val rows by app.store.messages.collectAsStateWithLifecycle()
    val playing by app.playingMessageKey.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Private team BLE relay", style = MaterialTheme.typography.titleLarge)
            Text("Experimental · trusted team only", fontWeight = FontWeight.SemiBold)
            Hint("Short emergency text can pass through nearby team phones without Wi-Fi, a hotspot or internet. Keep Bluetooth on. This is not Bluetooth SIG Mesh or a replacement for emergency services.")
            Hint("Real three-phone relay verification is still required. Android may restrict background operation; keep iTantra open during testing. No range, delivery or battery-life guarantee.")
            if (relay.configured) Text("Team " + relay.teamId.take(8) + " · confirm the same team on each phone")
            Text(relay.detail)
            if (relay.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (relay.active) {
                Text("Nearby advertisements: ${relay.nearby} · queued relay packets: ${relay.queued}")
                Hint("Advertisements are not authenticated members. Delivered, played and acknowledged counts below come from authenticated messages; total team size is unknown.")
                OutlinedButton(app.relay::stop, Modifier.fillMaxWidth()) { Text("Stop relay") }
                Button(onSos, Modifier.fillMaxWidth(), enabled = !relay.starting && !talk.busy && !talk.awaitingRelease,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Open team SOS") }
            } else {
                if (relay.configured) Button(start, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !relay.starting) { Text("Start relay for 30 minutes") }
                OutlinedButton({ editing = !editing; code = "" }, Modifier.fillMaxWidth(), enabled = !relay.starting) {
                    Text(if (editing) "Close team setup" else if (relay.configured) "Change trusted team" else "Create or join trusted team")
                }
            }
            if (editing && !relay.active) {
                Hint("Share the complete code privately in person or by offline transfer. Anyone with it can read alerts and join this team. Member signatures identify devices, not verified people. Changing the code replaces this phone's pending relay queue; history is retained.")
                OutlinedTextField(code, { code = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("64-character team code") }, minLines = 2, maxLines = 4)
                Row {
                    TextButton({ code = RelayPacket.newCode() }) { Text("Generate new code") }
                    if (code.isNotBlank()) TextButton({ clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
                }
                Button({ confirm = true }, enabled = runCatching { RelayPacket.teamKey(code) }.isSuccess && !relay.starting) { Text("Use this trusted team") }
                Hint("Clear the clipboard after sharing. The app stores the team code wrapped with an Android Keystore key; it never includes it in diagnostic exports.")
            }
            Text("Recent BLE alerts", style = MaterialTheme.typography.titleMedium)
            val history = rows.filter { RelaySession.owns(it) }.take(10)
            if (history.isEmpty()) Hint("No BLE emergency messages yet. Nothing is sent by starting relay alone.")
            history.forEach { row ->
                VoiceBubble(row, playing == row.key, talk.busy, { app.replay(row) }, app::stop, { app.acknowledge(row.key) }, {})
                if (row.direction == "OUT") {
                    fun count(value: String) = value.split(',').count { it.isNotBlank() }
                    Hint("Delivered ${count(row.deliveredTo)} · played ${count(row.playedBy)} · acknowledged ${count(row.acknowledgedBy)}. Team total unknown.")
                }
            }
            Hint("Alerts have a maximum 5-minute local forwarding budget and 3 radio hops. Retries are bounded. Relayed means a nearby mailbox accepted the frame—not that the intended team received or heard it. Expired alerts require a new SOS confirmation.")
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Use this trusted team?") },
        text = { Text("Only share the code with people you trust. This is an experimental emergency-text network, not public SOS broadcasting.") },
        confirmButton = { TextButton({ confirm = false; app.relay.configure(code) { ok -> if (ok) { code = ""; editing = false } } }) { Text("Save team") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
