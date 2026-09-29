package org.itantra.app.diagnostics

import android.app.Application
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.itantra.app.BuildConfig
import org.itantra.app.audio.CapturedAudio
import org.itantra.app.core.*
import org.itantra.app.data.MessageEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Footprint(val apkBytes: Long? = null, val asrBytes: Long? = null, val vadBytes: Long? = null,
    val currentPssBytes: Long? = null, val peakPssBytes: Long? = null, val installedModelBytes: Long? = null)
data class PttRecord(
    val id: Long, val language: String, val asrModel: String? = null, val status: String,
    val captureMs: Double? = null, val finishReason: String? = null, val trailingSilenceMs: Long? = null,
    val asrMs: Double? = null, val captureEndToTranscriptMs: Double? = null, val asrRtf: Double? = null,
    val framedBytes: Long? = null, val bytesByTransport: Map<String, Long> = emptyMap(),
    val transport: String? = null, val expectedAcks: Int? = null, val deliveredAcks: Int = 0, val playedAcks: Int = 0,
    val playedAckMs: List<Double> = emptyList(), val ttsFirstPcmMs: Double? = null, val ttsMs: Double? = null,
    val ttsAudioMs: Double? = null, val receiptToSubmissionMs: Double? = null,
    val playbackMs: Double? = null, val playbackStatus: String = "PENDING", val playbackAttempt: Int = 0,
    val chunks: Int = 0, val technical: Map<String, Any> = emptyMap()
) {
    val ttsRtf get() = ttsMs?.let { ms -> ttsAudioMs?.takeIf { it > 0 }?.let { ms / it } }
}
data class MeasurementSession(
    val sessionId: String = UUID.randomUUID().toString(), val context: Map<String, Any> = emptyMap(),
    val footprint: Footprint = Footprint(), val sent: List<PttRecord> = emptyList(),
    val received: List<PttRecord> = emptyList(), val captures: List<PttRecord> = emptyList(),
    val technical: Map<String, Any> = emptyMap(), val cpuSamples: List<CpuSample> = emptyList()
)

/** Session-only measurements. Message keys/recipient identities never enter the export model. */
class Diagnostics {
    private val cpu = CpuUsage()
    private val mutable = MutableStateFlow(MeasurementSession())
    val state = mutable.asStateFlow()
    private var nextId = 0L // Never reset: late callbacks cannot hit a new session's records.
    private data class Binding(val event: Long, val targets: Set<String>, var delivered: Set<String> = emptySet(),
        var played: Set<String> = emptySet(), var status: String = "QUEUED", val timedPlayed: MutableSet<String> = mutableSetOf(),
        val unknownRecipients: Boolean = false)
    private val outgoing = mutableMapOf<String, Binding>()
    private val incoming = mutableMapOf<String, Long>()

    @Synchronized fun initialize(app: Application) {
        val apkBytes = (listOf(app.applicationInfo.sourceDir) + app.applicationInfo.splitSourceDirs.orEmpty()).sumOf { File(it).length() }
        mutable.value = mutable.value.copy(context = mapOf("appVersion" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE, "variant" to BuildConfig.FLAVOR,
            "deviceModel" to Build.MODEL, "androidVersion" to Build.VERSION.RELEASE, "androidApi" to Build.VERSION.SDK_INT),
            footprint = mutable.value.footprint.copy(apkBytes = apkBytes,
                vadBytes = app.assets.openFd("vad/silero_vad.int8.onnx").use { it.length }))
    }
    @Synchronized fun activeModel(bytes: Long?) { mutable.value = mutable.value.copy(footprint = mutable.value.footprint.copy(asrBytes = bytes)) }
    @Synchronized fun installedModelStorage(bytes: Long) { mutable.value = mutable.value.copy(footprint = mutable.value.footprint.copy(installedModelBytes = bytes)) }
    @Synchronized fun resourceMode(mode: String) { cpu.mode(mode) }
    @Synchronized fun sampleCpu() {
        cpu.sample(SystemClock.elapsedRealtime(), Process.getElapsedCpuTime())?.let { sample ->
            mutable.value = mutable.value.copy(cpuSamples = (mutable.value.cpuSamples + sample).takeLast(120))
        }
    }
    @Synchronized fun put(key: String, value: Any) { mutable.value = mutable.value.copy(technical = mutable.value.technical + (key to value)) }
    fun mark(key: String) = put(key, SystemClock.elapsedRealtimeNanos())
    @Synchronized fun sampleMemory() {
        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
        val bytes = memory.totalPss * 1024L
        mutable.value = mutable.value.copy(footprint = mutable.value.footprint.copy(currentPssBytes = bytes,
            peakPssBytes = maxOf(bytes, mutable.value.footprint.peakPssBytes ?: 0)))
    }
    @Synchronized fun newSession() {
        cpu.reset()
        outgoing.clear(); incoming.clear()
        mutable.value = MeasurementSession(context = mutable.value.context,
            footprint = mutable.value.footprint.copy(peakPssBytes = mutable.value.footprint.currentPssBytes))
    }
    private fun prune() {
        val sentIds = mutable.value.sent.map { it.id }.toSet()
        val receivedIds = mutable.value.received.map { it.id }.toSet()
        outgoing.entries.removeAll { it.value.event !in sentIds }
        incoming.entries.removeAll { it.value !in receivedIds }
    }
    private fun record(id: Long) = (mutable.value.sent + mutable.value.captures + mutable.value.received).find { it.id == id }
    private fun change(id: Long, edit: (PttRecord) -> PttRecord) {
        val s = mutable.value
        mutable.value = s.copy(sent = s.sent.map { if (it.id == id) edit(it) else it },
            captures = s.captures.map { if (it.id == id) edit(it) else it },
            received = s.received.map { if (it.id == id) edit(it) else it })
    }
    @Synchronized fun beginCapture(language: String, model: String, packId: String?, handsFree: Boolean = false): Long {
        val id = ++nextId
        mutable.value = mutable.value.copy(captures = (mutable.value.captures +
            PttRecord(id, language, model, "CAPTURING", technical =
                (packId?.let { mapOf("asrPackId" to it) } ?: emptyMap()) + ("captureMode" to if (handsFree) "HANDS_FREE" else "PTT"))).takeLast(10))
        return id
    }
    @Synchronized fun captured(id: Long, audio: CapturedAudio) = change(id) { it.copy(
        captureMs = audio.samples.size / 16.0, finishReason = audio.endpoint.reason?.name,
        trailingSilenceMs = audio.endpoint.trailingSilenceMs, status = "TRANSCRIBING",
        technical = it.technical + mapOf("captureStartElapsedNs" to audio.startedNs, "captureEndElapsedNs" to audio.endedNs,
            "sampleCount" to audio.samples.size, "sampleRateHz" to 16000, "audioSource" to audio.source,
            "neuralSpeechDetected" to audio.endpoint.speechDetected)) }
    @Synchronized fun transcribed(id: Long, result: RecognitionResult, transcriptNs: Long, cpuMs: Long) = change(id) {
        val end = it.technical["captureEndElapsedNs"] as? Long
        it.copy(asrMs = result.inferenceMs, asrRtf = if (result.audioSeconds > 0) result.inferenceMs / (1000 * result.audioSeconds) else null,
            captureEndToTranscriptMs = end?.let { ns -> (transcriptNs - ns).coerceAtLeast(0) / 1e6 }, status = "DRAFT",
            technical = it.technical + mapOf("transcriptElapsedNs" to transcriptNs, "processCpuTimeMs" to cpuMs))
    }
    @Synchronized fun discarded(id: Long) = change(id) { it.copy(finishReason = FinishReason.DISCARDED.name, status = "DISCARDED") }
    @Synchronized fun forDraft(existingId: Long?, language: String): Long {
        if (existingId != null && record(existingId) != null) return existingId
        val id = ++nextId
        // A draft can outlive its measurement session. Do not reuse its old capture timings.
        mutable.value = mutable.value.copy(captures = (mutable.value.captures +
            PttRecord(id, language, status = "DRAFT")).takeLast(10))
        return id
    }
    @Synchronized fun bind(id: Long?, row: MessageEntity, unknownRecipients: Boolean = false) {
        val event = id?.let(::record) ?: return
        if (row.channel != "VOICE") return
        if (mutable.value.sent.none { it.id == id }) mutable.value = mutable.value.copy(
            captures = mutable.value.captures.filterNot { it.id == id }, sent = (mutable.value.sent + event).takeLast(10))
        outgoing[row.key] = Binding(event.id, if (row.roomId.isBlank()) setOf(row.peerId) else ids(row.targets), unknownRecipients = unknownRecipients)
        change(event.id) { it.copy(status = "QUEUED", transport = row.transport, language = row.language) }
        refreshAcknowledgements(event.id)
        prune()
    }
    @Synchronized fun received(row: MessageEntity) {
        if (row.channel != "VOICE" || incoming.containsKey(row.key)) return
        val id = ++nextId
        incoming[row.key] = id
        mutable.value = mutable.value.copy(received = (mutable.value.received + PttRecord(id, row.language,
            status = "RECEIVED", framedBytes = row.wireBytes.toLong(), transport = row.transport,
            technical = mapOf("receiptElapsedNs" to row.receiptElapsedMs * 1_000_000))).takeLast(10))
        prune()
    }
    // Once per successful application-frame socket write, including retries and local fan-out.
    @Synchronized fun transmitted(key: String, bytes: Int, transport: String) {
        val binding = outgoing[key] ?: return
        change(binding.event) {
            val totals = it.bytesByTransport + (transport to ((it.bytesByTransport[transport] ?: 0) + bytes))
            it.copy(framedBytes = (it.framedBytes ?: 0) + bytes, bytesByTransport = totals,
                transport = totals.keys.joinToString(" / "), status = "SENT")
        }
        refreshAcknowledgements(binding.event)
    }
    @Synchronized fun acknowledge(key: String, recipient: String, played: Boolean, atNs: Long) {
        val b = outgoing[key] ?: return
        if (!b.unknownRecipients && recipient !in b.targets) return
        b.delivered = b.delivered + recipient
        if (played) {
            b.played = b.played + recipient
            if (b.timedPlayed.add(recipient)) change(b.event) { r ->
                val end = r.technical["captureEndElapsedNs"] as? Long
                r.copy(playedAckMs = if (end != null && atNs >= end) r.playedAckMs + (atNs - end) / 1e6 else r.playedAckMs)
            }
        }
        refreshAcknowledgements(b.event)
    }
    @Synchronized fun updateMessages(rows: List<MessageEntity>) {
        for (row in rows) outgoing[row.key]?.let { b ->
            b.status = row.delivery
            if (row.roomId.isNotBlank()) {
                b.delivered = b.delivered + ids(row.deliveredTo); b.played = b.played + ids(row.playedBy)
            } else if (row.delivery in listOf("DELIVERED", "PLAYED", "ACKNOWLEDGED")) b.delivered = b.targets
        }
        outgoing.values.map { it.event }.distinct().forEach(::refreshAcknowledgements)
    }
    private fun ids(value: String) = value.split(',').filter { it.isNotBlank() }.toSet()
    private fun refreshAcknowledgements(id: Long) {
        val parts = outgoing.values.filter { it.event == id }
        if (parts.any { it.unknownRecipients }) {
            val delivered = parts.flatMap { it.delivered }.toSet().size
            val played = parts.flatMap { it.played }.toSet().size
            change(id) { it.copy(expectedAcks = null, deliveredAcks = delivered, playedAcks = played,
                status = if (delivered > 0) "PARTIAL" else if (parts.any { p -> p.status == "EXPIRED" }) "EXPIRED"
                    else if (it.framedBytes != null) "SENT" else "QUEUED",
                technical = it.technical + ("recipientTotalUnknown" to true)) }
            return
        }
        val targets = parts.flatMap { it.targets }.toSet()
        val delivered = targets.count { target -> parts.filter { target in it.targets }.all { target in it.delivered } }
        val played = targets.count { target -> parts.filter { target in it.targets }.all { target in it.played } }
        change(id) { it.copy(expectedAcks = targets.size, deliveredAcks = delivered, playedAcks = played,
            status = when {
                targets.isNotEmpty() && played == targets.size -> "PLAYED"
                targets.isNotEmpty() && delivered == targets.size -> "DELIVERED"
                parts.any { p -> p.status == "FAILED" } -> "DELIVERY_FAILED"
                delivered > 0 -> "PARTIAL"
                it.framedBytes != null -> "SENT"
                else -> "QUEUED"
            }) }
    }
    @Synchronized fun beginPlayback(key: String): Long? {
        val id = incoming[key] ?: return null
        change(id) { it.copy(ttsFirstPcmMs = null, ttsMs = null, ttsAudioMs = null, receiptToSubmissionMs = null,
            playbackMs = null, playbackStatus = "SYNTHESIZING", playbackAttempt = it.playbackAttempt + 1, chunks = 0,
            technical = it.technical.filterKeys { name -> name == "receiptElapsedNs" }) }
        return id
    }
    @Synchronized fun synthesized(id: Long?, audio: PcmAudio, requestedNs: Long) {
        if (id == null) return
        change(id) { it.copy(ttsFirstPcmMs = it.ttsFirstPcmMs ?: audio.firstPcmElapsedNs?.let { first -> (first - requestedNs).coerceAtLeast(0) / 1e6 },
            ttsMs = (it.ttsMs ?: 0.0) + audio.synthesisMs,
            ttsAudioMs = (it.ttsAudioMs ?: 0.0) + audio.samples.size * 1000.0 / audio.sampleRate,
            chunks = it.chunks + 1, technical = it.technical + ("ttsLastSampleRateHz" to audio.sampleRate)) }
    }
    @Synchronized fun submitted(id: Long?, atNs: Long) {
        if (id == null) return
        change(id) {
            val receipt = it.technical["receiptElapsedNs"] as? Long
            it.copy(receiptToSubmissionMs = it.receiptToSubmissionMs ?: receipt?.takeIf { ns -> ns in 1..atNs }?.let { ns -> (atNs - ns) / 1e6 },
                playbackStatus = "PLAYING", technical = it.technical + ("lastSubmissionElapsedNs" to atNs))
        }
    }
    @Synchronized fun playedChunk(id: Long?, submittedNs: Long, completedNs: Long) {
        if (id != null) change(id) { it.copy(playbackMs = (it.playbackMs ?: 0.0) + (completedNs - submittedNs).coerceAtLeast(0) / 1e6) }
    }
    @Synchronized fun playbackFinished(id: Long?, status: String) {
        if (id != null) change(id) { it.copy(playbackStatus = status) }
    }

    // Allowlist only: never serialize MessageEntity, bindings, legacy raw values or Throwable.
    @Synchronized fun json(): String {
        val s = mutable.value
        fun array(records: List<PttRecord>) = JSONArray(records.map { r -> JSONObject().apply {
            put("eventId", r.id); put("language", r.language); put("asrModel", r.asrModel ?: JSONObject.NULL)
            put("status", r.status); put("captureDurationMs", r.captureMs ?: JSONObject.NULL)
            put("finishReason", r.finishReason ?: JSONObject.NULL); put("trailingSilenceMs", r.trailingSilenceMs ?: JSONObject.NULL)
            put("asrInferenceMs", r.asrMs ?: JSONObject.NULL); put("captureEndToTranscriptMs", r.captureEndToTranscriptMs ?: JSONObject.NULL)
            put("asrRtf", r.asrRtf ?: JSONObject.NULL); put("framedBytes", r.framedBytes ?: JSONObject.NULL)
            put("transport", r.transport ?: JSONObject.NULL); put("bytesByTransport", JSONObject(r.bytesByTransport))
            put("expectedAcknowledgements", r.expectedAcks ?: JSONObject.NULL); put("deliveredAcknowledgements", r.deliveredAcks)
            put("playedAcknowledgements", r.playedAcks); put("captureEndToPlayedAckMs", JSONArray(r.playedAckMs))
            put("ttsFirstPcmMs", r.ttsFirstPcmMs ?: JSONObject.NULL); put("ttsSynthesisMs", r.ttsMs ?: JSONObject.NULL)
            put("ttsAudioDurationMs", r.ttsAudioMs ?: JSONObject.NULL); put("ttsRtf", r.ttsRtf ?: JSONObject.NULL)
            put("receiptToAudioTrackSubmissionMs", r.receiptToSubmissionMs ?: JSONObject.NULL)
            put("playbackDurationMs", r.playbackMs ?: JSONObject.NULL); put("playbackStatus", r.playbackStatus)
            put("playbackAttempt", r.playbackAttempt); put("ttsChunks", r.chunks); put("technical", JSONObject(r.technical))
        } })
        return JSONObject().apply {
            put("schemaVersion", 2); put("sessionId", s.sessionId); put("context", JSONObject(s.context))
            put("footprint", JSONObject().apply {
                put("apkBytes", s.footprint.apkBytes ?: JSONObject.NULL); put("activeAsrModelBytes", s.footprint.asrBytes ?: JSONObject.NULL)
                put("vadModelBytes", s.footprint.vadBytes ?: JSONObject.NULL); put("currentPssBytes", s.footprint.currentPssBytes ?: JSONObject.NULL)
                put("peakSampledPssBytes", s.footprint.peakPssBytes ?: JSONObject.NULL)
                put("installedModelStorageBytes", s.footprint.installedModelBytes ?: JSONObject.NULL)
            })
            put("cpuSamples", JSONArray(s.cpuSamples.map { sample -> JSONObject().apply {
                put("mode", sample.mode); put("wallMs", sample.wallMs); put("processCpuMs", sample.processCpuMs)
                put("oneCorePercent", sample.oneCorePercent)
            } }))
            put("sentPtt", array(s.sent)); put("receivedPtt", array(s.received)); put("unsentCaptures", array(s.captures))
            put("metricDefinitions", JSONObject(DEFINITIONS))
        }.toString(2)
    }
    companion object {
        val DEFINITIONS = linkedMapOf(
            "session" to "Latest 10 sent PTT events, 10 received voice messages and 10 unsent captures in this in-memory session. New session clears measurements only. Missing values are null.",
            "captureDurationMs" to "Captured PCM samples / 16 kHz, including initial and trailing silence. Capture end is a local monotonic timestamp, not necessarily finger release.",
            "trailingSilenceMs" to "Continuous neural non-speech after speech detection; reset by speech. 32 ms windows; PTT threshold 2000 ms, hands-free configurable 500-1200 ms. Endpoint is the first complete window at/above the threshold.",
            "finishReason" to "MANUAL_RELEASE, TRAILING_SILENCE, MAX_DURATION (15 seconds), or DISCARDED (cancelled, no neural speech, unusable/failed transcription).",
            "asrInferenceMs" to "Accumulated recognizer processing wall time, including streaming work during PTT and the final flush. Excludes microphone waiting and model loading; not the whole capture duration.",
            "captureEndToTranscriptMs" to "Local capture end to completed transcription, including scheduling and inference. Not release-to-transcript.",
            "asrRtf" to "ASR inference time / captured audio duration; below 1.0 is faster than real time.",
            "framedBytes" to "Sent: successful application-frame socket writes plus 4-byte prefixes, including retries and local group fan-out. Received: accepted message frame plus prefix. Excludes TLS, radio/network overhead and other phones' traffic.",
            "acknowledgements" to "Unique intended recipients acknowledging all framed parts of this PTT. Played ACK is distinct from human acknowledgement. Counts remain pending across disconnects.",
            "captureEndToPlayedAckMs" to "Local capture end to each first played ACK per recipient and part; includes delivery, remote playback and returning acknowledgement. Never subtracts clocks from different phones.",
            "ttsFirstPcmMs" to "First synthesis request to first native PCM callback, including initialization and scheduling. All chunks share one record.",
            "ttsSynthesisMs" to "Sum of synthesis call wall times for all chunks and emergency repeats in the playback attempt, including initialization; excludes playback and gaps.",
            "ttsRtf" to "Total synthesis time / total generated PCM duration; below 1.0 is faster than real time.",
            "receiptToAudioTrackSubmissionMs" to "Local receipt to first successful AudioTrack write submission, including queue delay. AudioTrack submission is not exact audible sound onset.",
            "playbackDurationMs" to "Sum of submission-to-drain/stop durations of chunks and emergency repeats; excludes gaps. Replay replaces playback measurements with its latest attempt.",
            "footprint" to "APK plus splits; active ASR pack; total installed model files (including retained versions); bundled VAD; PSS sampled about every 1 s during activity/hands-free, 5 s otherwise. Sampled peak is not an exact allocation peak.",
            "processCpuTimeMs" to "Process CPU time from capture setup through transcription, including initial hands-free waiting and other threads; not a CPU percentage.",
            "cpuSamples" to "Latest 120 same-mode windows: process CPU delta / elapsed wall time * 100. One busy CPU core = 100%; no division by core count. Mode changes reset the baseline. Includes sampler/UI/radio overhead; not a controlled device benchmark.",
            "scope" to "Development measurements only. No WER, intelligibility or certified benchmark averages. No transcripts, peers, network addresses, encryption data, firmware fingerprints or exception details in exports."
        )
    }
}
