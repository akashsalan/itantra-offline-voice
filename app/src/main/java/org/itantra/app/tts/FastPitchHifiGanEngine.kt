package org.itantra.app.tts

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.itantra.app.core.AcousticTopology
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.PcmAudio
import org.itantra.app.core.SpeechSynthesizerEngine
import org.itantra.app.core.TtsRequest
import org.itantra.app.core.TtsTokenizer
import org.itantra.app.core.VoicePack
import org.json.JSONArray

internal object OnnxNative {
    init { System.loadLibrary("itantra_tts") }
    external fun available(): Boolean
    external fun runtimeVersion(): String
    external fun open(path: String, threads: Int): Long
    external fun close(handle: Long)
    external fun runInt64(handle: Long, tokens: LongArray, outShape: IntArray): FloatArray?
    external fun runFloat(handle: Long, values: FloatArray, bins: Int, frames: Int): FloatArray?

    /** Two-output encoder: returns features and fills [outDurations] per token. */
    external fun runEncoder(
        handle: Long,
        tokens: LongArray,
        outShape: IntArray,
        outDurations: IntArray
    ): FloatArray?
}

/**
 * Two-stage neural voice: FastPitch turns tokens into an 80-bin mel spectrogram,
 * HiFi-GAN turns that mel into a waveform.
 *
 * The vocoder runs in overlapping mel chunks. That bounds peak native memory on
 * a 4 GB-era phone, where a long utterance would otherwise allocate one large
 * activation buffer. It does not reduce total work.
 */
class FastPitchHifiGanEngine(private val pack: VoicePack) : SpeechSynthesizerEngine {
    private var acoustic = 0L
    private var decoder = 0L
    private var vocoder = 0L
    private var tokenizer: TtsTokenizer? = null
    @Volatile private var cancelled = false

    private val split get() = pack.topology == AcousticTopology.SPLIT

    val language: LanguageCode get() = pack.language

    override fun supports(language: LanguageCode) = language == pack.language

    override suspend fun prepare() = withContext(Dispatchers.IO) { load() }

    private fun load() {
        if (acoustic != 0L && vocoder != 0L && tokenizer != null && (!split || decoder != 0L)) return
        check(OnnxNative.available()) {
            "The on-device neural voice runtime is unavailable. Using the built-in voice instead."
        }
        if (tokenizer == null) {
            val symbols = JSONArray(pack.tokens.readText(Charsets.UTF_8))
            tokenizer = TtsTokenizer((0 until symbols.length()).map { symbols.getString(it) })
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        try {
            if (acoustic == 0L) {
                acoustic = OnnxNative.open(
                    (if (split) pack.encoder else pack.acoustic).absolutePath, threads
                )
            }
            if (split && decoder == 0L) decoder = OnnxNative.open(pack.decoder.absolutePath, threads)
            if (vocoder == 0L) vocoder = OnnxNative.open(pack.vocoder.absolutePath, threads)
        } catch (error: Throwable) {
            close()
            throw error
        }
        check(acoustic != 0L && vocoder != 0L && (!split || decoder != 0L)) {
            "Could not open the neural voice model"
        }
    }

    override suspend fun synthesize(request: TtsRequest): PcmAudio = withContext(Dispatchers.IO) {
        require(request.language == pack.language) { "Voice pack language mismatch" }
        require(request.text.isNotBlank()) { "No text to speak" }
        // Matches the wire limit; also keeps token and activation sizes bounded.
        require(request.text.toByteArray(Charsets.UTF_8).size <= 2048) { "Text too long to synthesize" }
        cancelled = false
        val started = System.nanoTime()
        load()
        val encoded = requireNotNull(tokenizer).encode(request.text)
        require(!encoded.isEmpty) { "No pronounceable characters for ${pack.language.displayName}" }

        val shape = IntArray(3)
        val mel: FloatArray
        val bins: Int
        val frames: Int
        if (split) {
            // Encoder yields per-token features plus a duration for each token.
            // The expansion between the two graphs is what keeps each graph's
            // output length a function of its own input length.
            val durations = IntArray(encoded.tokens.size)
            val features = OnnxNative.runEncoder(acoustic, encoded.tokens, shape, durations)
                ?: throw IllegalStateException("Neural voice produced no features")
            val channels = shape[1]
            val tokenCount = shape[2]
            check(channels > 0 && tokenCount == durations.size && features.size == channels * tokenCount) {
                "Unexpected encoder shape ${shape.toList()}"
            }
            val expanded = expandByDuration(features, channels, tokenCount, durations)
            check(expanded.frames in 1..MAX_FRAMES) {
                "Predicted speech length ${expanded.frames} frames is out of range"
            }
            mel = OnnxNative.runFloat(decoder, expanded.values, channels, expanded.frames)
                ?: throw IllegalStateException("Neural voice produced no spectrogram")
            bins = MEL_BINS
            frames = mel.size / bins
            check(frames > 0 && mel.size == bins * frames) { "Unexpected decoder output size" }
        } else {
            mel = OnnxNative.runInt64(acoustic, encoded.tokens, shape)
                ?: throw IllegalStateException("Neural voice produced no spectrogram")
            bins = shape[1]
            frames = shape[2]
            check(bins == MEL_BINS && frames > 0 && mel.size == bins * frames) {
                "Unexpected spectrogram shape ${shape.toList()}"
            }
        }

        var firstPcmNs: Long? = null
        var firstPcmElapsed: Long? = null
        val waveform = ArrayList<FloatArray>()
        var produced = 0
        var start = 0
        while (start < frames && !cancelled) {
            val end = minOf(start + CHUNK_FRAMES, frames)
            // Overlap gives the convolution stack context, so chunk seams do not click.
            val padded = maxOf(0, start - OVERLAP_FRAMES)
            val slice = sliceFrames(mel, bins, frames, padded, end)
            val audio = OnnxNative.runFloat(vocoder, slice, bins, end - padded)
                ?: throw IllegalStateException("Neural voice produced no audio")
            val trim = (start - padded) * HOP
            val usable = if (trim in 1..audio.size) audio.copyOfRange(trim, audio.size) else audio
            if (firstPcmNs == null) {
                firstPcmNs = System.nanoTime()
                firstPcmElapsed = SystemClock.elapsedRealtimeNanos()
            }
            waveform += usable
            produced += usable.size
            start = end
        }
        check(!cancelled) { "Speech was stopped" }

        val samples = ShortArray(produced)
        var index = 0
        for (chunk in waveform) {
            for (value in chunk) {
                val clamped = if (value > 1f) 1f else if (value < -1f) -1f else value
                samples[index++] = (clamped * 32767f).toInt().toShort()
            }
        }
        PcmAudio(
            samples = samples,
            sampleRate = pack.sampleRate,
            synthesisMs = (System.nanoTime() - started) / 1e6,
            firstPcmMs = firstPcmNs?.let { (it - started) / 1e6 },
            firstPcmElapsedNs = firstPcmElapsed
        )
    }

    private class Expanded(val values: FloatArray, val frames: Int)

    /**
     * Repeats each encoder column by its predicted duration.
     *
     * Input is [1, channels, tokens] row-major; output is [1, channels, frames]
     * where frames is the sum of the durations. Negative or zero durations are
     * treated as zero, matching the exporter's reference implementation.
     */
    private fun expandByDuration(
        features: FloatArray,
        channels: Int,
        tokens: Int,
        durations: IntArray
    ): Expanded {
        var frames = 0
        for (index in 0 until tokens) {
            val count = durations[index]
            if (count > 0) {
                frames += count
                require(frames <= MAX_FRAMES) { "Predicted speech is too long to synthesize" }
            }
        }
        require(frames > 0) { "Predicted speech has no duration" }
        val out = FloatArray(channels * frames)
        for (channel in 0 until channels) {
            val source = channel * tokens
            var target = channel * frames
            for (index in 0 until tokens) {
                val value = features[source + index]
                var remaining = durations[index]
                while (remaining > 0) {
                    out[target++] = value
                    remaining--
                }
            }
        }
        return Expanded(out, frames)
    }

    /** Extracts [from, to) frames from a [1, bins, frames] row-major mel buffer. */
    private fun sliceFrames(mel: FloatArray, bins: Int, frames: Int, from: Int, to: Int): FloatArray {
        val width = to - from
        if (from == 0 && to == frames) return mel
        val out = FloatArray(bins * width)
        for (bin in 0 until bins) {
            System.arraycopy(mel, bin * frames + from, out, bin * width, width)
        }
        return out
    }

    override fun stop() { cancelled = true }

    override fun close() {
        cancelled = true
        if (acoustic != 0L) { OnnxNative.close(acoustic); acoustic = 0L }
        if (decoder != 0L) { OnnxNative.close(decoder); decoder = 0L }
        if (vocoder != 0L) { OnnxNative.close(vocoder); vocoder = 0L }
        tokenizer = null
    }

    companion object {
        const val MEL_BINS = 80
        const val HOP = 256

        /** About 12 minutes of audio; the exported graph asserts the same bound. */
        const val MAX_FRAMES = 65_536

        /** About 2.9 s of audio per chunk at 22.05 kHz. */
        const val CHUNK_FRAMES = 250
        const val OVERLAP_FRAMES = 8
    }
}
