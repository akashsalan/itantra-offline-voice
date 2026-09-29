package org.itantra.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.HandsFreePresentation
import org.itantra.app.transport.LinkState
import org.itantra.app.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TalkPage(app: AppRuntime, micGranted: Boolean, enableMic: () -> Unit,
    onDevices: () -> Unit, onSos: () -> Unit, onModels: () -> Unit) {
    val talk by app.talk.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val link by app.link.collectAsStateWithLifecycle()
    val mode by app.mode.collectAsStateWithLifecycle()
    val room by app.lan.state.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val handsFree by app.handsFree.collectAsStateWithLifecycle()
    val packs by app.packs.collectAsStateWithLifecycle()
    val messages by app.store.messages.collectAsStateWithLifecycle()
    val playingKey by app.playingMessageKey.collectAsStateWithLifecycle()
    var languages by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var handsFreeHelp by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.handsFreeMode) {
        if (!settings.handsFreeMode) { transcriptOpen = false; handsFreeHelp = false }
    }
    val haptics = LocalHapticFeedback.current
    var wasRecording by remember { mutableStateOf(false) }
    LaunchedEffect(talk.recording) {
        // Auto-segment boundaries must not buzz into the microphone every turn.
        if (settings.haptics && !settings.handsFreeMode && (talk.recording || wasRecording))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasRecording = talk.recording
    }
    val conversation = messages.filter { it.channel == "VOICE" &&
        if (mode.isLan) it.roomId == room.roomId && it.roomId.isNotBlank() && it.targetId.isBlank()
        else it.roomId.isBlank() && (session.peerId.isEmpty() || it.peerId == session.peerId) }
    val draftVisible = talk.transcript.isNotBlank() && !talk.draftSent
    val scrolling = rememberLazyListState()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focus = LocalFocusManager.current
    val languagePicker: @Composable () -> Unit = {
        Box {
            AssistChip({ languages = true }, enabled = !talk.busy && !handsFree.active,
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(if (settings.handsFreeMode) "Your speech · " + talk.language.displayName
                    else speechChoiceName(talk.language, talk.packId) + if (talk.language == LanguageCode.OR) " · experimental" else "") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
            DropdownMenu(languages, { languages = false }) {
                packs.forEach { pack ->
                    DropdownMenuItem(text = { Text(speechChoiceName(pack.language, pack.profileId) +
                        (if (pack.experimental) " · experimental" else "") + if (pack.installed) "" else " · import pack") },
                        enabled = !talk.busy && !handsFree.active,
                        onClick = { languages = false; app.select(pack.language,
                            if (pack.language == LanguageCode.EN) pack.profileId else null) })
                }
            }
        }
    }
    // New messages and completed transcripts reveal the latest bubble without moving the controls.
    LaunchedEffect(conversation.firstOrNull()?.key, draftVisible, talk.transcript) {
        if (conversation.isNotEmpty() || draftVisible) {
            if (settings.reducedMotion) scrolling.scrollToItem(0) else scrolling.animateScrollToItem(0)
        }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!keyboardOpen || settings.handsFreeMode) TalkModeSwitch(settings.handsFreeMode,
            enabled = !talk.busy || handsFree.active) { enabled ->
            focus.clearFocus(); languages = false; options = false
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            app.setHandsFreeMode(enabled)
        }
        if (settings.handsFreeMode) {
            val playing = messages.firstOrNull { it.key == playingKey }
            val latest = conversation.firstOrNull { it.key == playingKey } ?: conversation.firstOrNull()
            HandsFreeCallScreen(
                presentation = HandsFreePresentation(active = handsFree.active, muted = handsFree.muted,
                    connected = session.ready, micGranted = micGranted, modelReady = talk.ready,
                    recording = talk.recording, busy = talk.busy, playing = playingKey != null,
                    incomingPlayback = playing?.direction == "IN",
                    emergencyPlayback = playing?.let { it.emergency && it.direction == "IN" } == true,
                    hasDraft = draftVisible, awaitingRelease = talk.awaitingRelease),
                peerName = session.peerName,
                connectionLabel = mode.label + if (session.ready) " · connected" else " · setup needed",
                reducedMotion = settings.reducedMotion,
                latestSpeaker = latest?.let { (if (it.emergency) "Alert · " else "") + if (it.direction == "IN") it.peerName else "You" },
                latestText = latest?.text,
                latestStatus = latest?.let { LanguageCode.fromCode(it.language).displayName + " · " +
                    if (it.direction == "IN") "Speech " + it.playback.lowercase().replace('_', ' ') else deliveryLabel(it) },
                onStart = { focus.clearFocus(); app.startHandsFree() },
                onMute = { app.muteHandsFree(!handsFree.muted) }, onEnd = app::endHandsFree,
                onConnect = onDevices, onEnableMic = enableMic, onModels = onModels,
                onReviewDraft = { app.setHandsFreeMode(false) },
                onTranscript = { transcriptOpen = true }, onHelp = { handsFreeHelp = true },
                modifier = Modifier.weight(1f),
                languageControl = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        languagePicker()
                        if (talk.language == LanguageCode.OR) Hint("Experimental Odia · accuracy not validated.", MaterialTheme.colorScheme.tertiary)
                    }
                })
        } else {
        // Only surface the route when there is something real to say about it.
        // "Finding nearby phones" duplicated the status chip in the top bar and
        // pushed the conversation down, so idle and discovering states are hidden.
        val showRoute = session.ready || link is LinkState.Connected ||
            link is LinkState.Connecting || link is LinkState.Failed
        if (showRoute) {
            if (mode.isLan) LanConnectionCard(app, onDevices)
            else ConnectionCard(session.ready, session.peerName, link, mode.label, onDevices)
        }
        if (!keyboardOpen) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            languagePicker()
            AssistChip({ options = true }, label = { Text(if (settings.autoSend) "Auto-send on" else "Auto-send off") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        if (talk.language == LanguageCode.OR) Hint("Experimental Odia ASR · accuracy not validated. Check your transcript before sending.", MaterialTheme.colorScheme.tertiary)
        if (talk.emergency) Hint("Emergency draft · use SOS to review and confirm. Nothing is sent automatically.", MaterialTheme.colorScheme.error)
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = AppDesign.Card,
            color = MaterialTheme.colorScheme.background) {
            if (conversation.isEmpty() && !draftVisible) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(AppIcons.Radio, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(if (settings.autoSend) "Hold the microphone and speak.\nAuto-send is on, so your words go out when you finish."
                        else "Hold the microphone and speak.\nReview your words, then tap Send.",
                        style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else LazyColumn(state = scrolling, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draftVisible) item(key = "voice-draft") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Card(Modifier.fillMaxWidth(.9f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("You · draft, not sent", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(talk.transcript, app::editTranscript, Modifier.fillMaxWidth(),
                                    enabled = !talk.busy, minLines = 1, maxLines = 4, shape = AppDesign.Control,
                                    label = { Text("Review transcript") })
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton({ app.editTranscript("") }, enabled = !talk.busy) { Text("Discard draft") }
                                }
                            }
                        }
                    }
                }
                items(conversation, key = { it.key }) { message ->
                    VoiceBubble(message, playingKey == message.key, talk.busy, { app.replay(message) }, app::stop,
                        { app.acknowledge(message.key) }, { app.retry(message.key) })
                }
            }
        }
        if (!micGranted) Button(enableMic, Modifier.fillMaxWidth()) { Text("Enable microphone") }
        val canStart by rememberUpdatedState(micGranted && talk.ready && !talk.busy && !talk.awaitingRelease)
        val isRecording by rememberUpdatedState(talk.recording)
        val awaitingRelease by rememberUpdatedState(talk.awaitingRelease)
        val processing = talk.busy && !talk.recording
        val pttColour by animateColorAsState(
            if (processing) MaterialTheme.colorScheme.tertiaryContainer else if (talk.recording || canStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            tween(if (settings.reducedMotion) 0 else AppDesign.MotionMs), label = "Recording state")
        val halo by animateFloatAsState(if (talk.recording) 1f else 0f,
            tween(if (settings.reducedMotion) 0 else AppDesign.MotionMs), label = "Recording halo")
        val scale by animateFloatAsState(if (talk.recording && !settings.reducedMotion) 1.035f else 1f,
            tween(if (settings.reducedMotion) 0 else AppDesign.MotionMs), label = "PTT press")
        val haloColour = MaterialTheme.colorScheme.primary
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val pttLabel = when {
                talk.recording -> if (talk.phase.startsWith("Pause")) "Finishing" else "Listening"
                talk.awaitingRelease -> "Release to talk again"
                processing -> if (talk.phase.startsWith("Transcribing")) "Finishing" else "Please wait"
                else -> "Hold to speak"
            }
            // Exactly one status line at a time. Previously the label, the phase
            // text and the blocker hint all fired together and said the same
            // thing three different ways.
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
              val blocked = !canStart && !talk.recording && !talk.awaitingRelease && !processing
              Text(if (blocked && !talk.ready && micGranted) "Choose a language" else pttLabel,
                  style = MaterialTheme.typography.titleMedium)
              when {
                blocked -> {
                    val detail = when {
                        !micGranted -> "Tap Enable microphone above."
                        talk.loadingPackId != null -> "Getting ${talk.language.displayName} ready…"
                        !talk.ready -> "${talk.language.displayName} is not installed yet."
                        else -> "Finishing the last recording."
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!talk.ready && talk.loadingPackId == null && micGranted) {
                        Button(onModels, Modifier.heightIn(min = 48.dp).padding(top = 4.dp),
                            shape = AppDesign.Control) {
                            Icon(AppIcons.Models, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Install a language")
                        }
                    }
                }
                processing -> Text(talk.phase, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
                talk.recording -> Text(talk.phase, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                !session.ready -> Text("Saved as a draft until you connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("Speaks on ${session.peerName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (talk.busy) TextButton(app::stop, Modifier.heightIn(min = 48.dp)) { Text(if (talk.recording) "Discard" else "Stop") }
                if (draftVisible) Button({ if (talk.emergency) onSos() else app.sendText() }, modifier = Modifier.heightIn(min = 48.dp), shape = AppDesign.Control,
                    enabled = session.ready && !talk.busy) { Text(if (talk.emergency) "Review SOS" else "Send") }
            }
          }
          Box(Modifier.size(AppDesign.PttWidth + 12.dp, AppDesign.PttHeight + 12.dp).drawBehind {
              if (halo > 0) drawRoundRect(haloColour.copy(alpha = .22f * halo), Offset(3.dp.toPx(), 3.dp.toPx()),
                  Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(34.dp.toPx()), style = Stroke(3.dp.toPx()))
          }, contentAlignment = Alignment.Center) {
            Surface(color = pttColour,
                contentColor = if (processing) MaterialTheme.colorScheme.onTertiaryContainer else if (talk.recording || canStart) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = AppDesign.Hero, modifier = Modifier.size(AppDesign.PttWidth, AppDesign.PttHeight).graphicsLayer { scaleX = scale; scaleY = scale }
                    .semantics {
                        role = Role.Button
                        stateDescription = talk.phase
                        contentDescription = if (talk.recording) "Recording voice message" else if (talk.awaitingRelease) "Release to talk again" else "Hold to speak"
                        if (!canStart && !talk.recording && !talk.awaitingRelease) disabled()
                        onClick(if (talk.recording) "Finish recording" else "Start recording") {
                            if (isRecording || awaitingRelease) app.releaseRecording() else if (canStart) { focus.clearFocus(); app.startRecording() }
                            true
                        }
                    }.pointerInput(app) {
                        detectTapGestures(onPress = {
                            if (canStart) {
                                focus.clearFocus()
                                app.startRecording()
                                try {
                                    if (tryAwaitRelease()) app.releaseRecording() else app.cancelRecording()
                                } finally { app.releaseRecording() }
                            }
                        })
                    }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Microphone, null, Modifier.size(40.dp))
                }
            }
          }
        }
        if (talk.emergency && !talk.busy) TextButton({ app.setEmergency(false) }) { Text("Use normal voice mode") }
        }
    }
    if (transcriptOpen && settings.handsFreeMode) ModalBottomSheet(onDismissRequest = { transcriptOpen = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Voice transcript", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton({ transcriptOpen = false }) { Icon(Icons.Default.Close, "Close transcript") }
            }
            Hint("Saved on this phone. Replay pauses hands-free listening.")
            if (conversation.isEmpty()) EmptyCard("No voice messages yet", "Sent words and incoming voice replies appear here.")
            else LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversation, key = { it.key }) { message ->
                    VoiceBubble(message, playingKey == message.key, talk.busy, { app.replay(message) }, app::stop,
                        { app.acknowledge(message.key) }, { app.retry(message.key) })
                }
            }
            if (handsFree.active) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ app.muteHandsFree(!handsFree.muted) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (handsFree.muted) "Unmute" else "Mute")
                }
                TextButton(app::endHandsFree, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("End hands-free") }
            }
        }
    }
    if (handsFreeHelp && settings.handsFreeMode) ModalBottomSheet(onDismissRequest = { handsFreeHelp = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("A conversation, one turn at a time", style = MaterialTheme.typography.titleLarge)
            Text("Start hands-free turns on your microphone, sends your recognized speech automatically, and plays incoming voice replies. Each person starts it on their own phone.")
            Text("Speak when you see Listening. After a pause, your phone finishes the text and sends it. Listening pauses during processing and speech playback, then resumes.")
            Text("This is not a simultaneous audio call. There is no ringing or shared turn lock; avoid speaking over each other. Only text and message details travel across your local connection.")
            Text("Your selected language is for recognizing your speech. Incoming speech uses the message's language automatically; it is not translated.")
            Text("Mute or End cancels unfinished outgoing speech. Already queued messages are not recalled. End and switching back to push to talk keep your connection; disconnect in Connections to leave it.")
            Text("To change the pause length, open More †’ Settings †’ Voice messages. Push-to-talk Auto-send and Auto-play preferences do not control an active hands-free session.")
            Button({ handsFreeHelp = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Got it") }
        }
    }
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Voice options", style = MaterialTheme.typography.titleLarge)
            VoiceOptions(app)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable internal fun VoiceBubble(
    message: MessageEntity, playing: Boolean, audioBusy: Boolean,
    replay: () -> Unit, stop: () -> Unit, acknowledge: () -> Unit, retry: () -> Unit
) {
    val incoming = message.direction == "IN"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Card(Modifier.widthIn(max = 360.dp).fillMaxWidth(.9f),
            shape = if (incoming) AppDesign.IncomingBubble else AppDesign.OutgoingBubble,
            colors = CardDefaults.cardColors(containerColor = if (message.emergency) MaterialTheme.colorScheme.errorContainer
                else if (incoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (incoming) message.peerName else "You", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (message.emergency) Text("ALERT", style = MaterialTheme.typography.labelSmall)
                    IconButton(if (playing) stop else replay, enabled = !(playing && incoming && message.emergency &&
                        !org.itantra.app.relay.PublicRelaySession.owns(message)), modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow,
                            if (playing) "Stop audio" else if (audioBusy) "Queue audio replay" else "Replay synthesized speech", Modifier.size(22.dp))
                    }
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                Hint(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAtMs)) + " · " +
                    LanguageCode.fromCode(message.language).displayName + " · " +
                    if (incoming) "Speech " + message.playback.lowercase().replace('_', ' ') else deliveryLabel(message))
                if (incoming && !message.humanAcknowledged) TextButton(acknowledge, Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Acknowledge") }
                if (!incoming && message.delivery == "FAILED") TextButton(retry, Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            }
        }
    }
}

