package org.itantra.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.itantra.app.core.HandsFreePresentation
import org.itantra.app.core.HandsFreeStage

/** Selecting the view is deliberately separate from consent to capture/send. */
@Composable internal fun TalkModeSwitch(handsFree: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Surface(shape = AppDesign.Card, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().toggleable(value = handsFree, enabled = enabled,
            role = Role.Switch, onValueChange = change).semantics {
                stateDescription = if (handsFree) "Hands-free view" else "Push-to-talk view"
            }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (handsFree) AppIcons.Call else AppIcons.Radio, null,
                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Hands-free", style = MaterialTheme.typography.titleMedium)
                Text(if (handsFree) "Automatic turns" else "Off · hold to speak",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Parent owns the single, labelled accessibility/touch action.
            Switch(checked = handsFree, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable internal fun HandsFreeCallScreen(
    presentation: HandsFreePresentation,
    peerName: String,
    connectionLabel: String,
    reducedMotion: Boolean,
    latestSpeaker: String?,
    latestText: String?,
    latestStatus: String?,
    onStart: () -> Unit,
    onMute: () -> Unit,
    onEnd: () -> Unit,
    onConnect: () -> Unit,
    onEnableMic: () -> Unit,
    onModels: () -> Unit,
    onReviewDraft: () -> Unit,
    onTranscript: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    languageControl: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize().semantics { paneTitle = "Hands-free conversation" }) {
        val largeText = LocalDensity.current.fontScale >= 1.4f
        val scrollControls = maxHeight < 400.dp || largeText
        val compact = maxHeight < 540.dp || largeText
        val scroll = rememberScrollState()
        val body: @Composable ColumnScope.() -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (presentation.connected) peerName.ifBlank { "Your conversation" } else "No conversation connected",
                        style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(connectionLabel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onConnect) { Icon(AppIcons.Connections, "Connection details") }
            }
            CallStatus(presentation, reducedMotion, compact)
            Box(Modifier.align(Alignment.CenterHorizontally)) { languageControl() }
            if (presentation.active) Text("End hands-free to change your speech language.",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(onClick = onTranscript, shape = AppDesign.Card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().semantics { onClick(label = "Open voice transcript", action = null) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Latest exchange", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(AppIcons.Messages, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (latestText.isNullOrBlank()) {
                        Text("Your conversation will appear here.", style = MaterialTheme.typography.bodyMedium)
                        Text("View transcript", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(latestSpeaker.orEmpty(), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(latestText, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        latestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (scrollControls) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body()
                CallControls(presentation, onStart, onMute, onEnd, onConnect, onEnableMic,
                    onModels, onReviewDraft, onHelp)
            }
        } else Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
            CallControls(presentation, onStart, onMute, onEnd, onConnect, onEnableMic,
                onModels, onReviewDraft, onHelp)
        }
    }
}

@Composable private fun CallStatus(ui: HandsFreePresentation, reducedMotion: Boolean, compact: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val duration = if (reducedMotion) 0 else AppDesign.MotionMs
    val tone by animateColorAsState(when (ui.stage) {
        HandsFreeStage.ALERT -> scheme.error
        HandsFreeStage.MUTED -> scheme.onSurfaceVariant
        HandsFreeStage.PROCESSING, HandsFreeStage.PLAYBACK -> scheme.tertiary
        else -> scheme.primary
    }, tween(duration), label = "Call state colour")
    val listening = ui.stage == HandsFreeStage.LISTENING
    // A single state transition, never a repeating animation or fabricated sound level.
    val emphasis by animateFloatAsState(if (listening) 1f else .94f, tween(duration), label = "Call state emphasis")
    Surface(shape = AppDesign.Hero, color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(if (compact) 104.dp else 136.dp).graphicsLayer { scaleX = emphasis; scaleY = emphasis }
                .drawBehind {
                    drawCircle(tone.copy(alpha = .12f), radius = size.minDimension / 2)
                    drawCircle(tone.copy(alpha = .24f), radius = size.minDimension / 2 - 6.dp.toPx(), style = Stroke(1.dp.toPx()))
                }, contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = tone.copy(alpha = .12f), contentColor = tone,
                    modifier = Modifier.size(if (compact) 72.dp else 96.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(when (ui.stage) {
                            HandsFreeStage.MUTED -> AppIcons.MicOff
                            HandsFreeStage.PLAYBACK, HandsFreeStage.ALERT -> AppIcons.Speaker
                            HandsFreeStage.PROCESSING, HandsFreeStage.BUSY -> AppIcons.Diagnostics
                            HandsFreeStage.LISTENING -> AppIcons.Microphone
                            else -> AppIcons.Call
                        }, null, Modifier.size(if (compact) 36.dp else 44.dp))
                    }
                }
            }
            Column(Modifier.semantics(mergeDescendants = true) {
                // Don't automatically speak every state change into an armed microphone.
                // TalkBack can still focus/read this status; a real-device audit is pending.
                if (!ui.active) liveRegion = LiveRegionMode.Polite
            },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                Text(ui.stage.detail, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(ui.microphoneLabel, style = MaterialTheme.typography.labelMedium, color = tone)
            }
        }
    }
}

@Composable private fun CallControls(
    ui: HandsFreePresentation, onStart: () -> Unit, onMute: () -> Unit, onEnd: () -> Unit,
    onConnect: () -> Unit, onEnableMic: () -> Unit, onModels: () -> Unit,
    onReviewDraft: () -> Unit, onHelp: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.active) {
                val muteControl: @Composable (Modifier) -> Unit = { modifier ->
                    FilledTonalButton(onMute, modifier.heightIn(min = 64.dp), shape = AppDesign.Card) {
                        Icon(if (ui.muted) AppIcons.MicOff else AppIcons.Microphone, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (ui.muted) "Unmute" else "Mute")
                    }
                }
                val endControl: @Composable (Modifier) -> Unit = { modifier ->
                    Button(onEnd, modifier.heightIn(min = 64.dp)
                        .semantics { contentDescription = "End hands-free" }, shape = AppDesign.Card,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) {
                        Icon(AppIcons.EndCall, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("End")
                    }
                }
                if (LocalDensity.current.fontScale >= 1.4f) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        muteControl(Modifier.fillMaxWidth())
                        endControl(Modifier.fillMaxWidth())
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    muteControl(Modifier.weight(1f))
                    endControl(Modifier.weight(1f))
                }
                Text("End stops your microphone, not your connection.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val action = when (ui.stage) {
                    HandsFreeStage.CONNECT -> "Choose connection" to onConnect
                    HandsFreeStage.MICROPHONE -> "Enable microphone" to onEnableMic
                    HandsFreeStage.MODEL -> "Choose speech model" to onModels
                    HandsFreeStage.DRAFT -> "Review draft" to onReviewDraft
                    HandsFreeStage.RELEASE -> "Return to push to talk" to onReviewDraft
                    else -> "Start hands-free" to onStart
                }
                Button(action.second, Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = AppDesign.Card,
                    enabled = ui.canStart || ui.stage in setOf(HandsFreeStage.CONNECT, HandsFreeStage.MICROPHONE,
                        HandsFreeStage.MODEL, HandsFreeStage.DRAFT, HandsFreeStage.RELEASE)) {
                    Icon(if (ui.canStart) AppIcons.Call else Icons.Default.ArrowForward, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(action.first, textAlign = TextAlign.Center)
                }
            }
            TextButton(onHelp, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Offline · one turn at a time")
            }
        }
    }
}

// Stateless IDE previews: no AppRuntime, microphone, models, database or network access.
@Preview(name = "Ready · light", widthDp = 393, heightDp = 690, showBackground = true)
@Composable private fun HandsFreeReadyPreview() = CallPreview("LIGHT", HandsFreePresentation(
    connected = true, micGranted = true, modelReady = true))

@Preview(name = "Listening · dark", widthDp = 393, heightDp = 690, showBackground = true)
@Composable private fun HandsFreeListeningPreview() = CallPreview("DARK", HandsFreePresentation(
    active = true, connected = true, micGranted = true, modelReady = true, busy = true, recording = true))

@Preview(name = "Muted · large text", widthDp = 320, heightDp = 568, fontScale = 2f, showBackground = true)
@Composable private fun HandsFreeLargeTextPreview() = CallPreview("LIGHT", HandsFreePresentation(
    active = true, muted = true, connected = true, micGranted = true, modelReady = true))

@Preview(name = "Reply · landscape", widthDp = 640, heightDp = 300, showBackground = true)
@Composable private fun HandsFreeReplyPreview() = CallPreview("DARK", HandsFreePresentation(
    active = true, connected = true, micGranted = true, modelReady = true, busy = true,
    playing = true, incomingPlayback = true))

@Preview(name = "Processing · small phone", widthDp = 320, heightDp = 568, showBackground = true)
@Composable private fun HandsFreeProcessingPreview() = CallPreview("LIGHT", HandsFreePresentation(
    active = true, connected = true, micGranted = true, modelReady = true, busy = true))

@Preview(name = "Connection needed", widthDp = 393, heightDp = 690, showBackground = true)
@Composable private fun HandsFreeSetupPreview() = CallPreview("LIGHT", HandsFreePresentation())

@Composable private fun CallPreview(theme: String, ui: HandsFreePresentation) {
    ItantraTheme(theme) {
        Surface {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TalkModeSwitch(true, true) {}
                HandsFreeCallScreen(ui, "Field team", "Same Wi-Fi · " + if (ui.connected) "connected" else "setup needed", true,
                    "Teammate", "We have reached the meeting point.", "English · speech played",
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.weight(1f)) {
                    AssistChip({}, label = { Text("Your speech · English") }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
                }
            }
        }
    }
}
