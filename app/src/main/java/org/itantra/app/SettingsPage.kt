package org.itantra.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun MorePage(app: AppRuntime, export: () -> Unit, clearHistory: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf("More") }
    BackHandler(destination != "More") { destination = "More" }
    Column(Modifier.fillMaxSize()) {
        if (destination != "More") Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ destination = "More" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to More") }
            Text("More", style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.weight(1f)) {
            when (destination) {
                "Diagnostics" -> DiagnosticsPage(app, export)
                "Accessibility" -> AccessibilityPage(app)
                "Settings" -> PreferencesPage(app, clearHistory)
                "About and licences" -> AboutPage()
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("More", "Tools and preferences")
                    Surface(shape = AppDesign.Card, color = MaterialTheme.colorScheme.surface) {
                      Column {
                    listOf(
                        "Diagnostics" to "PTT timings, footprint and test-session export",
                        "Accessibility" to "Haptics, motion and spoken guidance",
                        "Settings" to "Voice options, appearance and emergency playback",
                        "About and licences" to "App version, offline operation and model notices"
                    ).forEach { (title, subtitle) ->
                        ListItem(headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
                            leadingContent = { Icon(when (title) {
                                "Diagnostics" -> AppIcons.Diagnostics
                                "Accessibility" -> AppIcons.Accessibility
                                "Settings" -> AppIcons.Settings
                                else -> AppIcons.Models
                            }, null, tint = MaterialTheme.colorScheme.primary) },
                            supportingContent = { Text(subtitle) }, trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { destination = title }.heightIn(min = 72.dp))
                        if (title != "About and licences") HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                      }
                    }
                    Hint("All speech inference and phone-to-phone communication work offline after model installation.")
                }
            }
        }
    }
}

@Composable internal fun VoiceOptions(app: AppRuntime) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    SettingSwitch("Auto-send voice messages", "When connected, send after transcription. Otherwise keep a draft. SOS always needs confirmation.",
        settings.autoSend, enabled = !talk.busy) { app.saveSettings(settings.copy(autoSend = it)) }
    SettingSwitch("Auto-play voice replies", "Normal voice replies. Emergency alerts still auto-play; text chat stays silent.", settings.autoSpeak, app::setAutoPlay)
    SettingSwitch("Finish PTT after 2-second pause", "For held recordings only. Hands-free uses the pause setting below.",
        settings.finishAfterPause, enabled = !talk.busy) { app.saveSettings(settings.copy(finishAfterPause = it)) }
    Text("Hands-free pause · ${settings.handsFreePauseMs} ms")
    Slider(settings.handsFreePauseMs.toFloat(), { app.saveSettings(settings.copy(handsFreePauseMs = it.toInt())) },
        enabled = !talk.busy, valueRange = 500f..1200f, steps = 6)
    Hint("Hands-free sends automatically and plays replies while its session is active, regardless of the PTT auto-send/auto-play preferences.")
    SettingSwitch("Allow emergency volume boost", "Temporarily use maximum alarm volume, then restore it. Android can still restrict playback.",
        settings.emergencyBoost) { app.saveSettings(settings.copy(emergencyBoost = it)) }
    if (!settings.emergencyBoost) Hint("Emergency maximum-volume setup is incomplete: alerts currently use your existing alarm volume.", MaterialTheme.colorScheme.tertiary)
}

@Composable private fun AccessibilityPage(app: AppRuntime) {
    val settings by app.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Accessibility", "Large controls, explicit states and optional feedback")
        SettingSwitch("Haptic feedback", "Feel recording start/finish and emergency confirmation.", settings.haptics) {
            app.saveSettings(settings.copy(haptics = it))
        }
        SettingSwitch("Reduce motion", "Use immediate transitions. Android's animation settings are also respected.", settings.reducedMotion) {
            app.saveSettings(settings.copy(reducedMotion = it))
        }
        SettingSwitch("Spoken connection guidance", "Optional English or Hindi setup guidance. Never listens to the microphone and pauses during speech activity.", settings.spokenGuidance) {
            app.saveSettings(settings.copy(spokenGuidance = it))
        }
        OutlinedButton({ app.connectionGuidance(force = true) }) { Text("Hear connection guidance") }
        Hint("TalkBack: activate the PTT button to start, then activate it again to finish. Automatic pause finish still requires a new activation before the next recording.")
        Hint("Text follows your Android font-size setting. Language names and messages retain native scripts. Full interface localisation is still pending review.")
    }
}

@Composable private fun PreferencesPage(app: AppRuntime, clearHistory: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    var name by remember(settings.name) { mutableStateOf(settings.name) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Settings", "Preferences stay on this phone")
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (value, label) ->
                FilterChip(settings.theme == value, { app.saveSettings(settings.copy(theme = value)) }, { Text(label) })
            }
        }
        HorizontalDivider()
        Text("Voice messages", style = MaterialTheme.typography.titleMedium)
        VoiceOptions(app)
        Text("Speech rate · " + settings.rate + " words/min")
        Slider(settings.rate.toFloat(), { app.saveSettings(settings.copy(rate = it.toInt())) }, valueRange = 100f..230f, steps = 12)
        HorizontalDivider()
        Text("Emergency playback", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Allow emergency volume boost", "Temporarily use maximum alarm volume, then restore it. Best effort under Android policy.", settings.emergencyBoost) {
            app.saveSettings(settings.copy(emergencyBoost = it))
        }
        Text("Repeat safety limit")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { count -> FilterChip(settings.emergencyRepeats == count,
                { app.saveSettings(settings.copy(emergencyRepeats = count)) }, { Text("$count time" + if (count == 1) "" else "s") }) }
        }
        HorizontalDivider()
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Your phone's name") })
        OutlinedButton({ app.saveSettings(settings.copy(name = name)) }) { Text("Save name for next connection") }
        TextButton(clearHistory) { Text("Clear local message history…") }
        Hint("Clearing a measurement session in Diagnostics does not clear message history.")
    }
}

@Composable private fun AboutPage() {
    val context = LocalContext.current
    var notices by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var licenceFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedLicence by remember { mutableStateOf<String?>(null) }
    var licenceText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) { if (expanded && notices == null) notices = withContext(Dispatchers.IO) {
        runCatching { context.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } }
            .getOrDefault("Notices could not be opened. Retry after reopening this page.")
    } }
    LaunchedEffect(expanded) { if (expanded && licenceFiles.isEmpty()) licenceFiles = withContext(Dispatchers.IO) {
        fun files(path: String): List<String> {
            val children = context.assets.list(path).orEmpty()
            return if (children.isEmpty()) listOf(path) else children.flatMap { files("$path/$it") }
        }
        runCatching { files("licenses").sorted() }.getOrDefault(emptyList())
    } }
    LaunchedEffect(selectedLicence) {
        licenceText = null
        selectedLicence?.let { path -> licenceText = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                .getOrDefault("Could not open the bundled licence. Reopen this page to retry.")
        } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("About iTantra", "Version " + BuildConfig.VERSION_NAME)
        Text("Speak locally. Send compact text. Recreate speech on the receiving phone.")
        Hint("English STT: Moonshine Small Streaming, with Tiny Streaming for low-end devices. TTS: embedded eSpeak NG formant synthesis—not neural TTS. Silero VAD runs during PTT or an explicitly started hands-free session.")
        Hint("No hosted speech API, automatic translation or guaranteed emergency playback. Language accuracy and radio range are not certified by the app.")
        Hint("Original application: GPL-3.0-or-later. This development build's complete corresponding-source distribution is still a release gate; the maintainer email is not a substitute.")
        OutlinedButton({ selectedLicence = if (selectedLicence == "licenses/espeak-ng-GPL-3.0.txt") null else "licenses/espeak-ng-GPL-3.0.txt" }) { Text("Full GPLv3 licence") }
        OutlinedButton({ expanded = !expanded }) { Text(if (expanded) "Hide third-party notices" else "Third-party notices and licences") }
        if (expanded) {
            Text(notices ?: "Opening bundled notices…", style = MaterialTheme.typography.bodySmall)
            licenceFiles.forEach { path -> TextButton({ selectedLicence = path }) { Text(path.removePrefix("licenses/")) } }
        }
        if (selectedLicence != null) {
            Text(selectedLicence!!.removePrefix("licenses/"), fontWeight = FontWeight.SemiBold)
            TextButton({ selectedLicence = null }) { Text("Close licence") }
            Text(licenceText ?: "Opening licence…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
