package org.itantra.app

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.itantra.app.data.MessageEntity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.itantra.app.core.*
import org.itantra.app.relay.RelaySession
import org.itantra.app.relay.PublicRelaySession
import org.json.JSONObject

private data class EmergencyTemplate(val title: String, val text: String)

/**
 * Colours for one SOS route.
 *
 * The two routes do not make the same promise, so they must not look the same.
 * Connected SOS is red: a known audience, reached directly. Public relay is amber:
 * it travels through strangers who may or may not be listening, so it reads as
 * caution rather than command, and a glance at the screen tells you which one you
 * are about to use.
 */
private data class SosTheme(
    val accent: Color, val onAccent: Color,
    val container: Color, val onContainer: Color
)

@Composable private fun sosTheme(publicRoute: Boolean): SosTheme {
    val scheme = MaterialTheme.colorScheme
    return if (publicRoute) SosTheme(scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer)
    else SosTheme(scheme.error, scheme.onError, scheme.errorContainer, scheme.onErrorContainer)
}

/** Full-screen, foreground-only confirmation. Opening this page never listens or transmits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SosPage(
    app: AppRuntime, micGranted: Boolean, enableMic: () -> Unit, onBack: () -> Unit,
    onConnect: () -> Unit, onRelay: () -> Unit, onModels: () -> Unit, initialBleRoute: Boolean,
    initialPublicRoute: Boolean = false, startPublicRelay: () -> Unit = {}, onPublicRouteChanged: (Boolean) -> Unit = {}
) {
    val talk by app.talk.collectAsStateWithLifecycle()
    val voice by app.sosVoice.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val mode by app.mode.collectAsStateWithLifecycle()
    val room by app.lan.state.collectAsStateWithLifecycle()
    val relay by app.relay.state.collectAsStateWithLifecycle()
    val publicRelay by app.publicRelay.state.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val messages by app.store.messages.collectAsStateWithLifecycle()
    var bleRoute by remember(initialBleRoute) { mutableStateOf(initialBleRoute) }
    var publicRoute by remember(initialPublicRoute) { mutableStateOf(initialPublicRoute) }
    val audience = remember(mode, room, session, relay, publicRelay, bleRoute, publicRoute) { app.emergencyAudienceKey(bleRoute, publicRoute) }
    val audienceLabel = if (publicRoute) "Nearby public SOS participants" else if (bleRoute) "Your trusted relay team" else if (room.group && mode.isLan) "Everyone in " + room.title
        else session.peerName.ifBlank { "Current conversation" }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    val gate = remember { SosConfirmation() }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var text by remember { mutableStateOf(voice.text) }
    var textLanguage by remember { mutableStateOf(if (text.isNotBlank()) voice.language else talk.language) }
    var captureId by remember { mutableStateOf<Long?>(if (!voice.sent) voice.captureId else null) }
    var shownCapture by remember { mutableStateOf(voice.captureId) }
    var awaitingCapture by remember { mutableStateOf<Long?>(null) }
    var capturedAudience by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(voice.sent && voice.messageKeys.isNotEmpty()) }
    var remaining by remember { mutableLongStateOf(3000) }
    var note by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    var confirmTyped by remember { mutableStateOf(false) }
    var templates by remember(talk.language) { mutableStateOf<List<EmergencyTemplate>?>(null) }
    val tooLong = (bleRoute || publicRoute) && text.toByteArray(Charsets.UTF_8).size > RelayPacket.MAX_TEXT_BYTES
    val sentRows = messages.filter { it.key in voice.messageKeys }
    val theme = sosTheme(publicRoute)
    // Alerts that belong to the tab you are looking at. The public tab shows only
    // public relay traffic; the connected tab shows everything else, which includes
    // the private team relay because that is reachable from this same tab.
    val recent = remember(messages, publicRoute) {
        messages.filter { it.emergency && PublicRelaySession.owns(it) == publicRoute }
            .sortedByDescending { it.createdAtMs }.take(8)
    }

    fun cancelPending(message: String) {
        gate.cancel(); armed = false; awaitingCapture = null; confirmTyped = false; note = message
    }
    // A relay that stops while it is the selected route must not silently fall back
    // to the connected conversation: that would retarget a pending alert.
    LaunchedEffect(relay.active) { if (bleRoute && !relay.active) bleRoute = false }
    fun leave(action: () -> Unit) {
        if (sending) return
        if (!done) app.saveEmergencyDraft(text, textLanguage, captureId)
        cancelPending(""); app.cancelEmergencyRecording(); action()
    }
    BackHandler { leave(onBack) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                cancelPending("Sending cancelled when the app left the screen.")
                app.cancelEmergencyRecording()
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { gate.cancel(); app.cancelEmergencyRecording(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(talk.language) {
        templates = withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(context.assets.open("emergency/${talk.language.code}.json").bufferedReader().use { it.readText() })
                require(json.getString("language") == talk.language.code)
                val rows = json.getJSONArray("templates")
                (0 until rows.length()).map { rows.getJSONObject(it).let { row -> EmergencyTemplate(row.getString("title"), row.getString("text")) } }
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(audience) {
        if (armed || awaitingCapture != null || confirmTyped) {
            cancelPending("Connection or recipients changed. Your words will not be sent automatically.")
            // Let the current capture finish as a draft; never retarget it to new people.
        }
    }
    // Bind the completed capture, not the shared Talk transcript. No duplicate on finger release.
    LaunchedEffect(voice.captureId, voice.completed, talk.busy) {
        if (voice.completed && !voice.sent && !talk.busy && voice.captureId != shownCapture) {
            shownCapture = voice.captureId
            text = voice.text; textLanguage = voice.language; captureId = voice.captureId
            if (awaitingCapture == voice.captureId) {
                awaitingCapture = null
                val draft = SosDraft(capturedAudience, voice.language, voice.text, voice.captureId)
                val fits = !(bleRoute || publicRoute) || voice.text.toByteArray(Charsets.UTF_8).size <= RelayPacket.MAX_TEXT_BYTES
                if (foreground && fits && gate.armVoice(draft, app.emergencyAudienceKey(bleRoute, publicRoute), SystemClock.elapsedRealtime())) {
                    remaining = 3000; armed = true; note = ""
                } else note = if (!fits) "Alert saved here. Shorten the message for BLE before sending."
                    else "Alert saved here—not sent. Choose an available route, then confirm Send SOS."
            }
        } else if (!talk.busy && awaitingCapture != null && !voice.completed) {
            awaitingCapture = null; note = "No usable speech captured. Nothing sent. Hold again or choose a preset."
        }
    }
    LaunchedEffect(armed) {
        if (armed) {
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            while (isActive && armed) {
                remaining = gate.remaining(SystemClock.elapsedRealtime())
                if (remaining == 0L) {
                    val draft = gate.consume(app.emergencyAudienceKey(bleRoute, publicRoute), SystemClock.elapsedRealtime())
                    armed = false
                    if (draft != null && foreground) {
                        sending = true
                        app.sendEmergency(draft) { ok ->
                            sending = false; done = ok
                            note = if (ok) "Queued. Watch for a delivery acknowledgement below."
                                else "Could not queue the alert. Your words are still here. Check the route and retry."
                        }
                    } else note = "Not sent. Review your recipients and try again."
                    break
                }
                delay(100)
            }
        }
    }
    var wasRecording by remember { mutableStateOf(false) }
    LaunchedEffect(talk.recording) {
        if (settings.haptics && (talk.recording || wasRecording)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasRecording = talk.recording
    }
    val canStart by rememberUpdatedState(foreground && micGranted && talk.ready && !talk.busy && !talk.awaitingRelease && !armed && !sending && !done)
    val recording by rememberUpdatedState(talk.recording)
    val held by rememberUpdatedState(talk.awaitingRelease)
    val begin by rememberUpdatedState {
        if (canStart && app.startEmergencyRecording()) {
            capturedAudience = app.emergencyAudienceKey(bleRoute, publicRoute)
            awaitingCapture = app.sosVoice.value.captureId
            text = ""; captureId = null; note = ""; typed = false
        }
    }
    val colour by animateColorAsState(if (talk.recording) theme.container
        else if (canStart) theme.accent else MaterialTheme.colorScheme.surfaceVariant,
        tween(if (settings.reducedMotion) 0 else 160), label = "SOS recording state")
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ leave(onBack) }, enabled = !sending) { Icon(Icons.Default.ArrowBack, "Back to Talk; cancel unsent SOS") }
            Text("Emergency SOS", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Icon(AppIcons.Radio, null, Modifier.size(28.dp), tint = theme.accent)
        }
        SecondaryTabRow(selectedTabIndex = if (publicRoute) 1 else 0, contentColor = theme.accent) {
            listOf("Connected SOS", "BLE Relay SOS").forEachIndexed { index, label ->
                Tab(selected = publicRoute == (index == 1), enabled = !talk.busy && !sending && !talk.awaitingRelease,
                    onClick = {
                        if (publicRoute != (index == 1)) {
                            cancelPending("Route changed. Review the alert before sending.")
                            if (done) { text = ""; captureId = null; done = false; typed = false }
                            publicRoute = index == 1; onPublicRouteChanged(publicRoute)
                        }
                    }, text = { Text(label) })
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // BLE relay has one prerequisite, so it comes before anything else on
            // that tab: with the switch off there is no route and nothing can be
            // sent. The connected tab has no prerequisite, so it opens straight on
            // the alerts.
            if (publicRoute) PublicRelayControls(app, startPublicRelay, theme.accent, settings.reducedMotion)
            SosThread(recent, theme, publicRoute)
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when {
                armed -> "Sending in ${(remaining + 999) / 1000} seconds"
                sending -> "Queueing emergency…"
                talk.recording || talk.busy -> talk.phase
                talk.awaitingRelease -> "Release your finger before recording again"
                done -> "Check delivery below"
                else -> "${speechChoiceName(talk.language, talk.packId)} · offline speech recognition"
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontWeight = FontWeight.SemiBold)
            if (!micGranted && !done) OutlinedButton(enableMic, Modifier.fillMaxWidth()) { Text("Allow microphone for SOS") }
            if (!talk.ready && !talk.busy && !done) OutlinedButton({ leave(onModels) }, Modifier.fillMaxWidth()) { Text("Install speech model") }
            // Keep this node alive through automatic endpointing/countdown: lifting the same
            // finger cannot dispatch again or cancel a capture that has already completed.
            Surface(color = colour, contentColor = if (talk.recording) theme.onContainer
                else if (canStart) theme.onAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = AppDesign.Hero, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Emergency push to talk"
                        stateDescription = if (talk.recording) talk.phase else "Hold to record; after transcription you have three seconds to cancel"
                        if (!canStart && !recording && !held) disabled()
                        onClick(if (recording || held) "Finish emergency recording" else "Start emergency recording") {
                            if (recording || held) app.releaseEmergencyRecording() else begin()
                            true
                        }
                    }.pointerInput(app) {
                        detectTapGestures(onPress = {
                            if (canStart) {
                                begin()
                                try { if (tryAwaitRelease()) app.releaseEmergencyRecording() else app.cancelEmergencyRecording() }
                                finally { app.releaseEmergencyRecording() }
                            }
                        })
                    }) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Microphone, null, Modifier.size(32.dp))
                    Text(if (talk.recording) "Release to finish SOS" else if (talk.awaitingRelease) "Lift your finger" else "Hold to record SOS",
                        Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            // Can't speak, or too noisy? These must sit right under the record
            // button, not be buried as a text link further up the page.
            if (!armed && !sending && !done && !talk.recording && !talk.awaitingRelease) {
                Text("Can't speak right now?", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ typed = true; textLanguage = talk.language },
                        Modifier.weight(1f).heightIn(min = 56.dp), shape = AppDesign.Control,
                        enabled = !talk.busy) {
                        Icon(AppIcons.Messages, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Type it")
                    }
                    OutlinedButton({ templateMenu = true },
                        Modifier.weight(1f).heightIn(min = 56.dp), shape = AppDesign.Control,
                        enabled = !talk.busy && !templates.isNullOrEmpty()) {
                        Icon(Icons.Default.List, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Quick pick")
                    }
                }
            }
            if (!armed && !sending && !done && text.any { it.isLetterOrDigit() }) Button({ confirmTyped = true },
                Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = AppDesign.Control,
                enabled = audience.isNotBlank() && !talk.busy && !tooLong && !talk.awaitingRelease,
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent, contentColor = theme.onAccent)) {
                Text("Review and send SOS", fontWeight = FontWeight.SemiBold)
            }
            if (done) OutlinedButton({ done = false; text = ""; captureId = null; note = ""; typed = false },
                Modifier.fillMaxWidth(), enabled = !talk.awaitingRelease) { Text("Record another SOS") }
            Hint(if (armed) "Cancel now to stop transmission." else if (settings.finishAfterPause)
                "Release or pause for 2 seconds to finish. Then 3 seconds to cancel."
                else "Release to finish. Then 3 seconds to cancel.")
            // The connected route needs a conversation before it can send, and the
            // fix sits with the controls rather than in a card of its own.
            if (!publicRoute && audience.isBlank() && !done && !armed && !sending)
                Button({ leave(onConnect) }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = AppDesign.Control, enabled = !talk.busy) {
                    Icon(AppIcons.Connections, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect a person or group")
                }
        }
            if (done) SosSentAnimation(publicRoute, settings.reducedMotion, theme.accent, theme.container, theme.onContainer)
            if (done) Text("Emergency sent", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            // One line of route state, not a card. Who it reaches is worth knowing;
            // it is not worth a boxed paragraph above the button you came here to press.
            if (!done) SosRouteLine(publicRoute, audience, audienceLabel, publicRelay.active, theme)
            if (text.isNotBlank() || typed) {
                if (!armed && !done && !sending && typed) OutlinedTextField(text, { text = it.take(8000); captureId = null },
                    Modifier.fillMaxWidth(), enabled = !talk.busy, label = { Text("Exact words to send") }, minLines = 2, maxLines = 5)
                else Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = AppDesign.Card) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (done) "Emergency text" else "Your emergency text · ${textLanguage.uiName}", style = MaterialTheme.typography.labelLarge)
                        Text(text.ifBlank { voice.text }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (tooLong) Hint("BLE alerts are limited to 1024 UTF-8 bytes. Cancel and shorten this message; it has not been sent.", MaterialTheme.colorScheme.error)
                if (!armed && !done && !sending && !talk.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ typed = !typed }) { Text(if (typed) "Done editing" else "Edit words") }
                    TextButton({ app.speak(text, textLanguage) }, enabled = text.isNotBlank()) { Text("Listen") }
                }
            }
            if (done) {
                Text("Delivery progress", style = MaterialTheme.typography.titleMedium)
                val sent = sentRows
                if (sent.isEmpty()) Hint("Waiting for the saved delivery record…")
                sent.forEachIndexed { index, row ->
                    if (sent.size > 1) Text("Part ${index + 1}")
                    Text(deliveryLabel(row))
                    if (RelaySession.owns(row) || PublicRelaySession.owns(row)) {
                        fun count(ids: String) = ids.split(',').count { it.isNotBlank() }
                        Hint("Delivered ${count(row.deliveredTo)} · played ${count(row.playedBy)} · acknowledged ${count(row.acknowledgedBy)}. Total recipients unknown.")
                    }
                    if (row.delivery in listOf("FAILED", "PARTIAL") && !RelaySession.owns(row) && !PublicRelaySession.owns(row)) TextButton({ app.retry(row.key) }) { Text("Retry undelivered alert") }
                }
                Hint("Acknowledged means someone saw it, not that help is coming.")
            }
            if (note.isNotBlank()) Text(note, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (!armed && !done && !sending && !talk.recording) {
                // The preset menu anchors here; "Quick pick" above opens it.
                Box {
                    DropdownMenu(templateMenu, { templateMenu = false }) {
                        templates.orEmpty().forEach { template -> DropdownMenuItem(text = { Text(template.title) }, onClick = {
                            text = template.text; textLanguage = talk.language; captureId = null; templateMenu = false; typed = true
                        }) }
                    }
                }
                if (templates == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (talk.language != LanguageCode.EN && templates?.isNotEmpty() == true)
                    Hint("Check the exact words of a preset before sending.")
            }
            // Everything explanatory lives behind one arrow. Three numbered steps
            // and the limits, in plain words, for whoever wants them.
            ExpandableSection(
                if (publicRoute) "How BLE relay SOS works" else "How SOS to your team works",
                settings.reducedMotion, accent = theme.accent
            ) {
                Text(if (publicRoute) "Any nearby phone with receiving on. No code or pairing, and you will not know who receives it."
                    else "Only the phones you are connected to. It plays at full volume even if they closed the app.",
                    fontWeight = FontWeight.SemiBold)
                SosSteps(publicRoute, theme)
                Hint("No internet needed. Alerts expire after 5 minutes and reach up to 3 phones deep.")
                Hint("Not a replacement for emergency services. Delivery is not guaranteed.")
                if (publicRoute) Hint("Public alerts are not encrypted. Senders are unverified.")
                // The trusted-team route used to be a radio choice in a bottom sheet
                // reached from a "Change" button. It is a rarely used advanced option,
                // so it lives here instead — still reachable, no longer in the way.
                if (!publicRoute) {
                    SettingSwitch("Send to your trusted relay team instead",
                        if (relay.active) "BLE relay is running. Experimental." else "Set up and start BLE relay on your team's phones first.",
                        bleRoute, relay.active && !talk.busy && !armed && !sending && !talk.awaitingRelease) { bleRoute = it }
                    TextButton({ leave(onRelay) }, enabled = !talk.busy && !armed && !sending) { Text("Set up private team relay") }
                }
            }
            if (publicRoute) PublicRelayHistory(app)
            Spacer(Modifier.height(4.dp))
        }
        // The only pinned element left: the cancel window. It appears for the
        // three seconds before transmission and while queueing, so a user who has
        // scrolled away can still stop it. Nothing is pinned at rest.
        if (armed || sending) Surface(shadowElevation = 8.dp,
            color = theme.container, contentColor = theme.onContainer) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (armed) Button({ cancelPending("Cancelled. Nothing sent. You can edit the alert or record again.") },
                    Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = AppDesign.Control) {
                    Text("Cancel SOS · ${(remaining + 999) / 1000}s", fontWeight = FontWeight.SemiBold)
                }
                if (sending) {
                    Text("Queueing emergency…", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (confirmTyped) AlertDialog(onDismissRequest = { confirmTyped = false }, title = { Text("Send emergency to $audienceLabel?") },
        text = { Text(text + (if (publicRoute) "\n\nThis is public, not encrypted. Nearby participants may read and forward it." else "") + "\n\nYou will have 3 seconds to cancel. Once sent, it cannot be recalled.") },
        confirmButton = { TextButton({
            confirmTyped = false
            if (foreground && gate.arm(SosDraft(audience, textLanguage, text, captureId), SystemClock.elapsedRealtime())) {
                remaining = 3000; armed = true; note = ""
            }
        }) { Text("Confirm SOS") } }, dismissButton = { TextButton({ confirmTyped = false }) { Text("Go back") } })
}

/**
 * The alerts already on this route, newest first — the same idea as a conversation
 * thread, kept short and sitting directly above the record button so you can see
 * what you have already sent before you send another.
 *
 * Read-only on purpose. This is a record, not a control: re-sending has to go back
 * through the record button and the three-second cancel window like any other
 * alert, because a one-tap resend next to a list is how people send SOS by mistake.
 */
@Composable private fun SosThread(rows: List<MessageEntity>, theme: SosTheme, publicRoute: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) rows else rows.take(3)
    val now = System.currentTimeMillis()
    Surface(Modifier.fillMaxWidth(), shape = AppDesign.Card,
        color = theme.container, contentColor = theme.onContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Radio, null, Modifier.size(18.dp))
                Text(if (publicRoute) "BLE relay alerts" else "Your SOS alerts",
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (rows.size > 3) TextButton({ expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (expanded) "Show less" else "All ${rows.size}", color = theme.onContainer)
                }
            }
            // An empty container still has to say what it is for, otherwise it just
            // looks broken the first time anyone opens this page.
            if (rows.isEmpty()) Text(
                if (publicRoute) "Alerts you send or receive over BLE relay will appear here."
                else "Alerts you send, and any you receive, will appear here.",
                style = MaterialTheme.typography.bodyMedium
            )
            shown.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Surface(shape = AppDesign.Pill, color = theme.accent, contentColor = theme.onAccent) {
                        Text(if (row.direction == "IN") "Received" else "Sent",
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.padding(start = 10.dp).weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.text.ifBlank { "(no words captured)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Conversations.relativeTime(row.createdAtMs, now) + " · " + deliveryLabel(row),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * One line naming who the alert will reach, or why it cannot go yet.
 *
 * This replaced a boxed card with a title, a subtitle, a hint and a button. The
 * information mattered; the box did not.
 */
@Composable private fun SosRouteLine(
    publicRoute: Boolean, audience: String, audienceLabel: String,
    relayActive: Boolean, theme: SosTheme
) {
    val ready = audience.isNotBlank()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Radio, null, Modifier.size(18.dp),
            tint = if (ready) theme.accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when {
                ready -> "Goes to $audienceLabel"
                publicRoute && !relayActive -> "Turn on public BLE SOS above to send"
                publicRoute -> "Starting BLE relay…"
                else -> "No one connected yet. You can still record and keep it here."
            },
            Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Three numbered steps, in plain words.
 *
 * A first-time user should be able to read this page once, under stress, and know
 * exactly what will happen. Replaces a paragraph that described the pipeline.
 */
@Composable private fun SosSteps(publicRoute: Boolean, theme: SosTheme) {
    val steps = listOf(
        "Hold the big button and say what happened, where you are, and what help you need.",
        "Your phone turns your voice into text. No internet is used.",
        if (publicRoute) "It hops phone to phone, up to 3 phones away. Only phones with receiving on will hear it."
        else "Your connected phones vibrate, then read it aloud."
    )
    Surface(Modifier.fillMaxWidth(), shape = AppDesign.Card,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(shape = AppDesign.Pill, color = theme.accent,
                        contentColor = theme.onAccent) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(step, Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
