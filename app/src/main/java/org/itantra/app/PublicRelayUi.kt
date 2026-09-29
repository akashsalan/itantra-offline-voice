package org.itantra.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.relay.PublicRelaySession

/**
 * The one switch that turns public BLE SOS on.
 *
 * It governs sending as well as receiving: `PublicRelaySession.audienceKey()` is
 * blank while the session is inactive, so with this off there is no route and an
 * alert cannot be sent. The copy has to say that plainly — on an emergency screen a
 * switch that looks optional but is actually required is worse than no switch.
 *
 * [accent] carries the BLE route's own colour so this card does not read as the
 * connected route.
 */
@Composable internal fun PublicRelayControls(
    app: AppRuntime, start: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.tertiary,
    reducedMotion: Boolean = false
) {
    val relay by app.publicRelay.state.collectAsStateWithLifecycle()
    val privateRelay by app.relay.state.collectAsStateWithLifecycle()
    var consent by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, accent.copy(alpha = if (relay.active) .7f else .3f))) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The switch and its state, nothing else. The reasoning is one tap away.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Radio, null, Modifier.size(22.dp), tint = accent)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(if (relay.active) "Receiving is on" else "Receiving is off",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (relay.active) "You can send and hear nearby alerts"
                            else "Needed to send or receive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(relay.active, onCheckedChange = { enabled ->
                        if (enabled) consent = true else app.stopPublicRelay()
                    }, enabled = !relay.starting)
                }
                if (relay.starting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(app::stopPublicRelay) { Text("Cancel starting") }
                }
                if (relay.active && !relay.starting)
                    Text("${relay.nearby} nearby · ${relay.queued} queued · ${relay.forwarded} forwarded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (privateRelay.active || privateRelay.starting) {
                    Hint("Private team relay is active. Only one BLE relay mode can run at a time.")
                    OutlinedButton(app.relay::stop) { Text("Stop private relay") }
                }
                // Attached to the switch it explains, not stacked underneath in a
                // second box. Two lines: what it does, and what it does not do.
                ExpandableRow("What this does", reducedMotion, accent = accent) {
                    Text("On: this phone joins the nearby BLE relay, so it can send an alert and will speak and forward ones it hears.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Turning it on sends nothing. It stops on its own after 30 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Live status from the session: this is where "Enable Bluetooth in
                // Android settings" surfaces, so it cannot be hidden behind the arrow.
                if (relay.detail.isNotBlank()) Hint(relay.detail)
            }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false },
        title = { Text("Turn on public BLE SOS?") },
        text = { Text("For up to 30 minutes, this phone will receive, speak once and automatically forward nearby public SOS alerts. No pairing or code is needed.\n\nSenders and their claims are unverified. Bluetooth stays active and uses battery. You can stop at any time; there is no automatic restart or guaranteed delivery.") },
        confirmButton = { TextButton({ consent = false; start() }, enabled = !privateRelay.active && !privateRelay.starting) { Text("Turn on receiving") } },
        dismissButton = { TextButton({ consent = false }) { Text("Not now") } })
}

@Composable internal fun PublicRelayHistory(app: AppRuntime) {
    val messages by app.store.messages.collectAsStateWithLifecycle()
    val playing by app.playingMessageKey.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val rows = messages.filter(PublicRelaySession::owns).take(10)
    TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide public SOS history" else "Public SOS history (${rows.size})") }
    if (expanded) {
        if (rows.isEmpty()) Hint("No public SOS alerts yet. Turning receiving on does not create an SOS.")
        rows.forEach { row ->
            VoiceBubble(row, playing == row.key, talk.busy, { app.replay(row) }, app::stop, { app.acknowledge(row.key) }, {})
            if (row.direction == "IN") Hint("Received at hop ${row.lanPayload.toIntOrNull() ?: "?"} of 3 · sender unverified")
            else {
                fun count(value: String) = value.split(',').count { it.isNotBlank() }
                Hint("Delivered ${count(row.deliveredTo)} · played ${count(row.playedBy)} · acknowledged ${count(row.acknowledgedBy)}")
            }
        }
        Hint("Receipts identify responding devices, not verified rescuers. An acknowledgement is not a promise of help.")
    }
}
