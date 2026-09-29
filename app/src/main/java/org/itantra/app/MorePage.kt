package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.core.LanguageCode
import org.itantra.app.diagnostics.*
import java.util.Locale

private const val NotMeasured = "Not measured yet"
private fun decimal(value: Double) = String.format(Locale.US, "%.2f", value)
private fun duration(ms: Double?): String = ms?.let {
    if (it < 1000) "${String.format(Locale.US, "%.0f", it)} ms" else "${decimal(it / 1000)} s"
} ?: NotMeasured
private fun size(bytes: Long?): String = bytes?.let {
    when { it >= 1_000_000 -> "${decimal(it / 1_000_000.0)} MB"; it >= 1000 -> "${decimal(it / 1000.0)} KB"; else -> "$it B" }
} ?: NotMeasured
private fun rtf(value: Double?) = value?.let(::decimal) ?: NotMeasured
private fun language(code: String?) = LanguageCode.entries.find { it.code == code }?.displayName ?: NotMeasured

@Composable internal fun DiagnosticsPage(app: AppRuntime, export: () -> Unit) {
    val session by app.diagnostics.state.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    var history by remember { mutableStateOf(false) }
    var technical by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Diagnostics", "Measured on this phone")
        // The seven quantities the problem statement asks for, in one place, with
        // "Not measured" shown honestly rather than a fabricated figure.
        MeasurementCard("Benchmark summary") {
            val sent = session.sent.lastOrNull()
            val received = session.received.lastOrNull()
            val idle = session.cpuSamples.filter { it.mode.startsWith("IDLE") }
            // Idle listening means the microphone is open and voice detection is
            // running with nobody talking: hands-free waiting for speech. Muted
            // hands-free is excluded because the microphone is not listening, and
            // CpuUsage never records ACTIVE samples, so that was never a real case.
            val listening = session.cpuSamples.filter { it.mode == "HANDS_FREE_WAITING" }
            fun cpu(samples: List<org.itantra.app.core.CpuSample>): String {
                val wall = samples.sumOf { sample -> sample.wallMs }
                if (wall <= 0L) return NotMeasured
                val used = samples.sumOf { sample -> sample.processCpuMs }
                return decimal(100.0 * used / wall) + "% of one core"
            }
            Measurement("Model size (active recognizer)", size(session.footprint.asrBytes))
            Measurement("App size (APK)", size(session.footprint.apkBytes))
            Measurement("RAM (peak sampled PSS)", size(session.footprint.peakPssBytes))
            // Labels say what each figure contains. "Idle" alone read as "doing
            // nothing", but it covers screens, animations and background timers.
            Measurement("CPU, app open (not listening)", cpu(idle))
            Measurement("CPU, idle listening (hands-free)", cpu(listening))
            Measurement("Speech-to-text RTF", sent?.asrRtf?.let { decimal(it) } ?: NotMeasured)
            Measurement("Text-to-speech RTF", received?.ttsRtf?.let { decimal(it) } ?: NotMeasured)
            Measurement("End-to-end (capture end to played)",
                received?.receiptToSubmissionMs?.let { duration(it) }
                    ?: sent?.playedAckMs?.firstOrNull()?.let { duration(it) } ?: NotMeasured)
            Measurement("Word error rate", NotMeasured)
            Hint("Word error rate needs a scored reference recording set; it is not computed on-device.")
        }
        Text("Measurement session", style = MaterialTheme.typography.titleMedium)
        Hint("Latest 10 sent and 10 received records · current session ${session.sessionId.take(8)}")
        OutlinedButton({ app.diagnostics.newSession() }, Modifier.fillMaxWidth(), enabled = !talk.busy) { Text("Start new measurement session") }
        Button(export, Modifier.fillMaxWidth()) { Text("Export test-session JSON") }
        Hint("Exports measurements and device context. Message text, peers and network addresses are excluded.")
        SentMeasurement("Latest sent PTT", session.sent.lastOrNull())
        ReceivedMeasurement("Latest received PTT", session.received.lastOrNull())
        session.captures.lastOrNull()?.let { SentMeasurement("Latest capture · not sent", it) }
        MeasurementCard("App and model footprint") {
            Measurement("APK size", size(session.footprint.apkBytes))
            Measurement("Active ASR model size", size(session.footprint.asrBytes))
            Measurement("All installed model files", size(session.footprint.installedModelBytes))
            Measurement("VAD model size", size(session.footprint.vadBytes))
            Measurement("Current app memory (PSS)", size(session.footprint.currentPssBytes))
            Measurement("Peak sampled app memory (PSS)", size(session.footprint.peakPssBytes))
            Measurement("Device model", session.context["deviceModel"]?.toString() ?: NotMeasured)
            Measurement("Android version", session.context["androidVersion"]?.toString() ?: NotMeasured)
            Hint("Memory is sampled about every second during activity/hands-free, every 5 seconds otherwise. Model storage includes retained versions. Sizes use decimal KB/MB.")
        }
        MeasurementCard("Idle process CPU · observed windows") {
            if (session.cpuSamples.isEmpty()) Text(NotMeasured)
            session.cpuSamples.groupBy { it.mode }.forEach { (mode, samples) ->
                val percent = 100.0 * samples.sumOf { it.processCpuMs } / samples.sumOf { it.wallMs }
                Measurement(mode.lowercase().replace('_', ' '), "${decimal(percent)}% over ${samples.sumOf { it.wallMs } / 1000} s")
            }
            Hint("100% means one fully occupied CPU core. Includes UI, radio and measurement overhead. Mode changes exclude mixed windows; these are observations, not controlled benchmark results.")
        }
        TextButton({ history = !history }) { Text(if (history) "Hide session records" else "Show session records (${session.sent.size} sent, ${session.received.size} received)") }
        if (history) {
            session.sent.asReversed().forEach { SentMeasurement("Sent PTT #${it.id}", it) }
            session.received.asReversed().forEach { ReceivedMeasurement("Received PTT #${it.id}", it) }
        }
        TextButton({ technical = !technical }) { Text((if (technical) "–¾ " else "–¸ ") + "Technical details") }
        if (technical) {
            Hint("Local monotonic clocks only. Per-capture CPU time is not CPU percentage. Idle-window percentages are separately defined. WER, intelligibility and controlled device benchmarks remain unmeasured.")
            (session.sent + session.received + session.captures).forEach { record ->
                Text("Event #${record.id}", fontWeight = FontWeight.SemiBold)
                (record.technical + mapOf("framedBytes" to (record.framedBytes ?: NotMeasured),
                    "bytesByTransport" to record.bytesByTransport, "ttsChunks" to record.chunks,
                    "playbackAttempt" to record.playbackAttempt, "captureEndToPlayedAckMs" to record.playedAckMs,
                    "captureDurationMs" to (record.captureMs ?: NotMeasured), "trailingSilenceMs" to (record.trailingSilenceMs ?: NotMeasured),
                    "asrInferenceMs" to (record.asrMs ?: NotMeasured), "captureEndToTranscriptMs" to (record.captureEndToTranscriptMs ?: NotMeasured),
                    "asrRtf" to (record.asrRtf ?: NotMeasured), "ttsFirstPcmMs" to (record.ttsFirstPcmMs ?: NotMeasured),
                    "ttsSynthesisMs" to (record.ttsMs ?: NotMeasured), "ttsRtf" to (record.ttsRtf ?: NotMeasured),
                    "receiptToAudioTrackSubmissionMs" to (record.receiptToSubmissionMs ?: NotMeasured),
                    "playbackDurationMs" to (record.playbackMs ?: NotMeasured))).forEach { (key, value) ->
                    Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                }
            }
            session.technical.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
            Diagnostics.DEFINITIONS.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
        }
        Hint("iTantra ${BuildConfig.VERSION_NAME} · offline speech")
    }
}

@Composable private fun MeasurementCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = AppDesign.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
@Composable private fun Measurement(label: String, value: String) {
    Column { Hint(label); Text(value, style = MaterialTheme.typography.bodyMedium) }
}
@Composable private fun SentMeasurement(title: String, r: PttRecord?) = MeasurementCard(title) {
    if (r == null) { Text(NotMeasured); return@MeasurementCard }
    Hint("Event #${r.id} · ${r.status.lowercase().replace('_', ' ')}")
    Measurement("Language / ASR model", "${language(r.language)} / ${r.asrModel ?: NotMeasured}")
    Measurement("Capture duration", duration(r.captureMs))
    Measurement("Finish reason", r.finishReason?.replace('_', ' ') ?: NotMeasured)
    Measurement("Detected trailing silence", duration(r.trailingSilenceMs?.toDouble()))
    Measurement("ASR inference time", duration(r.asrMs))
    Measurement("Capture-end-to-transcript", duration(r.captureEndToTranscriptMs))
    Measurement("ASR RTF", rtf(r.asrRtf))
    Hint("RTF below 1.0 is faster than real time.")
    Measurement("Transmitted framed bytes", size(r.framedBytes))
    Measurement("Transport", r.transport ?: NotMeasured)
    Measurement("Delivery acknowledgements", r.expectedAcks?.let { "${r.deliveredAcks} / $it recipients" }
        ?: if (r.technical["recipientTotalUnknown"] == true) "${r.deliveredAcks} confirmed · team total unknown" else "Not sent")
    Measurement("Playback acknowledgements", r.expectedAcks?.let { "${r.playedAcks} / $it recipients" }
        ?: if (r.technical["recipientTotalUnknown"] == true) "${r.playedAcks} confirmed · team total unknown" else "Not sent")
    if (r.playedAckMs.isNotEmpty()) Measurement("Capture-end-to-played-ACK", r.playedAckMs.joinToString { duration(it) })
    Hint("Played-ACK includes remote playback and the returning acknowledgement. Group counts require every part of this PTT to be acknowledged.")
}
@Composable private fun ReceivedMeasurement(title: String, r: PttRecord?) = MeasurementCard(title) {
    if (r == null) { Text(NotMeasured); return@MeasurementCard }
    Hint("Event #${r.id}")
    Measurement("Language", language(r.language))
    Measurement("Received framed bytes", size(r.framedBytes))
    Measurement("Transport", r.transport ?: NotMeasured)
    Measurement("TTS first-PCM time", duration(r.ttsFirstPcmMs))
    Measurement("Total TTS synthesis time", duration(r.ttsMs))
    Measurement("TTS RTF", rtf(r.ttsRtf))
    Hint("RTF below 1.0 is faster than real time. All TTS chunks in this playback attempt are combined.")
    Measurement("Receipt-to-AudioTrack-submission", duration(r.receiptToSubmissionMs))
    Hint("AudioTrack submission is not exact audible sound onset.")
    Measurement("Playback duration", duration(r.playbackMs))
    Measurement("Playback status", r.playbackStatus.lowercase().replace('_', ' '))
}

