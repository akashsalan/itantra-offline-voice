package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.PackCatalog
import java.util.Locale

internal val LanguageCode.nativeName: String get() = when (this) {
    LanguageCode.BN -> "বাংলা"; LanguageCode.EN -> "English"; LanguageCode.GU -> "ગુજરાતી"
    LanguageCode.HI -> "हिन्दी"; LanguageCode.KN -> "ಕನ್ನಡ"; LanguageCode.ML -> "മലയാളം"
    LanguageCode.MR -> "मराठी"; LanguageCode.OR -> "ଓଡ଼ିଆ"; LanguageCode.TA -> "தமிழ்"; LanguageCode.TE -> "తెలుగు"
}
internal val LanguageCode.uiName get() = if (this == LanguageCode.EN) displayName else nativeName + " / " + displayName
internal fun speechChoiceName(language: LanguageCode, packId: String?) = when (packId) {
    PackCatalog.englishMoonshineTiny.id -> "English — low-end devices"
    PackCatalog.englishParakeet.id -> "English (previous model)"
    else -> language.uiName
}
internal fun readableBytes(bytes: Long) = if (bytes >= 1_000_000) String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
    else if (bytes >= 1000) String.format(Locale.US, "%.2f KB", bytes / 1000.0) else "$bytes B"

@Composable internal fun ModelsPage(app: AppRuntime, importPack: () -> Unit, importVoice: () -> Unit) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Speech to text") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Text to speech") })
        }
        when (tab) {
            0 -> SpeechModelsList(app, importPack)
            else -> VoicesList(app, importVoice)
        }
    }
}

/**
 * Voices are additive. eSpeak NG ships inside the app for all ten languages, so a
 * language with no neural pack still speaks. Installing a pack only upgrades how
 * that one language sounds; it never affects recognition or message delivery.
 */
@Composable private fun VoicesList(app: AppRuntime, importVoice: () -> Unit) {
    val voices by app.voiceStatuses.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf<org.itantra.app.models.VoiceStatus?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Text to speech (voices)", "All 10 languages already speak.") }
        item {
            ReadyBanner("Built-in voice active for all 10 languages",
                "eSpeak is included and always used as the fallback. Adding a natural voice only changes how one language sounds.")
        }
        item {
            Button(importVoice, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !talk.busy) { Text("Import a voice") }
            Hint("Works fully offline. Copy a voice pack over USB, then pick it here.")
        }
        if (voices.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking installed voices…") }
        items(voices, key = { it.packId }) { voice ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(AppIcons.Speaker, voice.active)
                    Text(voice.language.uiName, Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.titleMedium)
                    if (voice.installed) TextButton(
                        { app.selectVoice(voice.language, if (voice.active) null else voice.packId) },
                        enabled = !talk.busy
                    ) { Text(if (voice.active) "Use built-in" else "Use natural") }
                }
                // Same tag row as the recogniser side, with one word changed that
                // matters: a voice is never required, because eSpeak already speaks
                // this language. Nothing here is broken if it is never installed.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusTag(if (voice.installed) "Installed" else "Import optional")
                    if (voice.active) StatusTag("Natural voice")
                    if (!voice.available) StatusTag("Built-in only", warning = true)
                }
                Hint(
                    when {
                        voice.installed && voice.active -> readableBytes(voice.bytes) + " installed · tap to use built-in"
                        voice.installed -> readableBytes(voice.bytes) + " installed · tap Use natural"
                        !voice.available -> "No natural voice published for this language yet"
                        else -> readableBytes(voice.bytes) + " installed size"
                    }
                )
                voice.error?.let { Hint(it, MaterialTheme.colorScheme.tertiary) }
                // Present but disabled, exactly as the recogniser side behaves when a
                // pack has no published URL. A verified download host is not part of
                // this build; showing the button keeps the two tabs consistent and
                // shows where it will appear, and the hint says why it cannot run yet.
                if (!voice.installed && voice.available) {
                    OutlinedButton({}, enabled = false) { Text("Download this voice") }
                    Hint("A verified download location has not been published. Import over USB for now.")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ details = if (details == voice.packId) null else voice.packId }) { Text("Size and verification details") }
                    if (voice.installed) TextButton({ delete = voice }, enabled = !talk.busy) { Text("Delete") }
                }
                if (details == voice.packId) {
                    Text("Installed payload: ${voice.bytes} bytes")
                    Hint("Every voice pack is checked against a pinned SHA-256 before it is activated. Import needs the pack size plus 16 MiB of safety space.")
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item { Hint("AI4Bharat Indic-TTS · MIT · quality not formally evaluated. Speech never leaves this phone.") }
    }
    delete?.let { selected ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text("Delete the " + selected.language.displayName + " natural voice?") },
            text = { Text("Frees ${readableBytes(selected.bytes)}. This language keeps speaking with the built-in voice.") },
            confirmButton = { TextButton({ delete = null; app.removeVoice(selected.language, selected.packId) }) { Text("Delete") } },
            dismissButton = { TextButton({ delete = null }) { Text("Keep") } })
    }
}

/** Reassures the user that something already works, before offering extras. */
@Composable internal fun ReadyBanner(title: String, detail: String) {
    Surface(Modifier.fillMaxWidth(), shape = AppDesign.Card,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Check, null, Modifier.size(20.dp))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun SpeechModelsList(app: AppRuntime, importPack: () -> Unit) {
    val packs by app.packs.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val download by app.downloads.state.collectAsStateWithLifecycle()
    val catalogue = remember(app) { app.downloads.catalogue }
    var delete by remember { mutableStateOf<org.itantra.app.models.PackStatus?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Speech to text", "Install the languages you speak.") }
        item {
            Button(importPack, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !talk.busy) { Text("Import a language") }
            Hint("Works fully offline. Copy a pack over USB, then pick it here.")
        }
        if (download.phase.isNotBlank()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(download.phase, fontWeight = FontWeight.SemiBold)
                    if (download.filename.isNotBlank()) Text(download.filename)
                    if (download.total > 0) {
                        LinearProgressIndicator(progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text(readableBytes(download.bytes) + " / " + readableBytes(download.total))
                    }
                    if (download.detail.isNotBlank()) Hint(download.detail)
                    if (download.active && !download.installing) Row {
                        TextButton(app.downloads::pause) { Text("Pause") }
                        TextButton({ download.language?.let(app.downloads::cancel) }) { Text("Cancel download") }
                    }
                }
            }
        }
        if (packs.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking installed models…") }
        items(packs, key = { it.profileId }) { pack ->
            val entry = catalogue.singleOrNull { it.packId == pack.profileId }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(AppIcons.Microphone, pack.installed)
                        Text(speechChoiceName(pack.language, pack.profileId),
                            Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        if (pack.installed) TextButton({ app.select(pack.language, pack.profileId) }, enabled = !talk.busy) {
                            Text(if (talk.packId == pack.profileId && talk.ready) "Selected" else "Use")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val loading = talk.busy && talk.loadingPackId == pack.profileId
                        StatusTag(if (loading) "Loading" else if (pack.installed) "Installed" else "Import required", warning = !pack.installed && !loading)
                        if (pack.experimental) StatusTag("Experimental", warning = true)
                    }
                    Hint(readableBytes(pack.bytes) + (if (pack.installed) " installed" else " installed size") +
                        (entry?.let { " · " + readableBytes(it.bytes) + " download" } ?: ""))
                    pack.error?.let { Hint(it, MaterialTheme.colorScheme.tertiary) }
                    if (pack.profileId == PackCatalog.englishMoonshineTiny.id)
                        Hint("Smaller and faster · may make more mistakes")
                    if (pack.experimental) Hint("Experimental · check each transcript", MaterialTheme.colorScheme.tertiary)
                    val partial = download.partials[pack.language] ?: 0L
                    if (!pack.installed && entry != null) {
                        OutlinedButton({ app.downloads.start(pack.language) }, enabled = entry.url != null && !download.active && !talk.busy) {
                            Text(if (partial > 0) "Resume / retry" else "Download this language")
                        }
                        if (partial > 0) {
                            Hint(readableBytes(partial) + " saved for resume")
                            TextButton({ app.downloads.cancel(pack.language) }, enabled = !download.active) { Text("Remove partial download") }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ details = if (details == pack.profileId) null else pack.profileId }) { Text("Size and verification details") }
                        if (pack.installed) TextButton({ delete = pack }, enabled = !talk.busy && !download.active) { Text("Delete") }
                    }
                    if (details == pack.profileId) {
                        Text("Installed payload: ${pack.bytes} bytes")
                        entry?.let { Text("File: ${it.filename}\nArchive: ${it.bytes} bytes\nSHA-256: ${it.sha256}", style = MaterialTheme.typography.bodySmall) }
                        Hint("Download plus extraction need both sizes temporarily, plus 32 MiB safety space. Filesystem overhead varies. Previously imported versions are not automatically removed.")
                    }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item {
            SettingSwitch("Download on Wi-Fi only", "Pauses when you leave this screen.",
                settings.wifiOnlyDownloads) { app.saveSettings(settings.copy(wifiOnlyDownloads = it)) }
            Hint("Speech never leaves this phone.")
        }
    }
    delete?.let { selected ->
        val active = talk.ready && talk.packId == selected.profileId
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete " + speechChoiceName(selected.language, selected.profileId) + " model?") },
            text = { Text(if (active) "This recogniser is currently loaded. Select another installed model first."
                else "Removes this installed model version (${readableBytes(selected.bytes)}). Messages and other languages are kept. USB import can restore it; bundled models will not silently reinstall.") },
            confirmButton = { TextButton({ delete = null; selected.packId?.let { app.removeModel(selected.language, it) } }, enabled = !active) { Text("Delete model") } },
            dismissButton = { TextButton({ delete = null }) { Text("Keep model") } })
    }
}
