package org.itantra.app.audio

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.itantra.app.core.*

data class CapturedAudio(val samples: ShortArray, val startedNs: Long, val endedNs: Long, val source: String,
    val endpoint: EndpointState)
class Microphone(private val assets: AssetManager) {
    private val endpoint = PttEndpoint()
    private var handsFreePauseMs: Int? = null
    val held get() = endpoint.snapshot().held
    fun arm(autoFinish: Boolean): Boolean {
        if (!endpoint.press(autoFinish)) return false
        handsFreePauseMs = null
        return true
    }
    fun armHandsFree(pauseMs: Int): Boolean {
        require(pauseMs in 500..1200)
        if (!endpoint.press(false)) return false
        handsFreePauseMs = pauseMs
        return true
    }
    fun release() = endpoint.release(SystemClock.elapsedRealtimeNanos())
    fun stop() { endpoint.finish(FinishReason.DISCARDED, SystemClock.elapsedRealtimeNanos()) }
    @SuppressLint("MissingPermission")
    suspend fun capture(onPcm: ((ShortArray) -> Unit)? = null, onSpeech: () -> Unit = {}, onPause: (Boolean) -> Unit): CapturedAudio = withContext(Dispatchers.IO) {
        var device: AudioRecord? = null
        var vad: NeuralVad? = null
        try {
            // Created only for an active press. Failure is closed: no amplitude fallback or send.
            if (!endpoint.snapshot().active) return@withContext CapturedAudio(ShortArray(0),
                SystemClock.elapsedRealtimeNanos(), endpoint.snapshot().endedNs!!, "Not started", endpoint.snapshot())
            vad = NeuralVad(assets)
            if (!endpoint.snapshot().active) return@withContext CapturedAudio(ShortArray(0),
                SystemClock.elapsedRealtimeNanos(), endpoint.snapshot().endedNs!!, "Not started", endpoint.snapshot())
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "16 kHz PCM microphone unsupported" }
            var sourceName = "VOICE_RECOGNITION"
            for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                val candidate = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    try { candidate.startRecording() } catch (_: IllegalStateException) { candidate.release(); continue }
                    if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) { device = candidate; sourceName = if (source == MediaRecorder.AudioSource.MIC) "MIC fallback" else "VOICE_RECOGNITION"; break }
                }
                candidate.release()
            }
            val active = checkNotNull(device) { "Microphone initialization failed" }
            val started = SystemClock.elapsedRealtimeNanos()
            handsFreePauseMs?.let { pauseMs ->
                val segment = HandsFreeSegmenter(pauseMs)
                val frame = ShortArray(NeuralVad.WINDOW)
                var filled = 0
                var announced = false
                var paused = false
                while (endpoint.snapshot().active && !segment.finished) {
                    ensureActive()
                    val read = active.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                    check(read > 0) { "Microphone stopped or became unavailable: $read" }
                    filled += read
                    if (filled != frame.size) continue
                    filled = 0
                    val update = segment.accept(frame, vad.speech(frame, 0))
                    if (segment.detected && !announced) { announced = true; onSpeech() }
                    update.pcm?.let { onPcm?.invoke(it) }
                    val nextPause = segment.trailingSilenceMs > 0
                    if (nextPause != paused) { paused = nextPause; onPause(paused) }
                }
                val ended = SystemClock.elapsedRealtimeNanos()
                segment.reason?.let { endpoint.finish(it, ended) }
                val samples = segment.samples()
                return@withContext CapturedAudio(samples, maxOf(started, ended - samples.size * 1_000_000_000L / 16000),
                    ended, sourceName, endpoint.snapshot().copy(speechDetected = segment.detected,
                        trailingSilenceMs = segment.trailingSilenceMs))
            }
            val buffer = ShortArray(16000 * 15)
            var count = 0
            var analyzed = 0
            var paused = false
            while (endpoint.snapshot().active && count < buffer.size) {
                ensureActive()
                val read = active.read(buffer, count, minOf(320, buffer.size - count), AudioRecord.READ_BLOCKING)
                check(read >= 0) { "AudioRecord read failed: $read" }
                if (read > 0) onPcm?.invoke(buffer.copyOfRange(count, count + read))
                count += read
                while (analyzed + NeuralVad.WINDOW <= count && endpoint.snapshot().active) {
                    endpoint.accept(vad.speech(buffer, analyzed), 32, SystemClock.elapsedRealtimeNanos())
                    analyzed += NeuralVad.WINDOW
                    val nextPause = endpoint.snapshot().trailingSilenceMs > 0
                    if (nextPause != paused) { paused = nextPause; onPause(paused) }
                }
                if (SystemClock.elapsedRealtimeNanos() - started >= 15_000_000_000L)
                    endpoint.finish(FinishReason.MAX_DURATION, SystemClock.elapsedRealtimeNanos())
            }
            endpoint.finish(FinishReason.MAX_DURATION, SystemClock.elapsedRealtimeNanos())
            val end = endpoint.snapshot()
            CapturedAudio(buffer.copyOf(count), started, maxOf(started, end.endedNs!!), sourceName, end)
        } finally {
            stop()
            device?.let { runCatching { it.stop() }; it.release() }
            vad?.close()
        }
    }
}
