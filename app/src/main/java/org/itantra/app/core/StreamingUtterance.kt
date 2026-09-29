package org.itantra.app.core

/** Native calls stay on one speech worker. No microphone, network, or endpoint timer here. */
interface StreamingDecoder : AutoCloseable {
    fun accept(samples: FloatArray)
    fun decode(final: Boolean): List<StreamingText>
    fun stop()
}
data class StreamingText(val id: Long, val text: String)

class StreamingUtterance(private val decoder: StreamingDecoder,
    private val nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    private var samples = 0
    private var lastDecode = 0
    private var decodeEverySamples = 8000
    private var inferenceNs = 0L
    private var closed = false
    private var result: RecognitionResult? = null
    private val lines = linkedMapOf<Long, String>()

    fun accept(pcm: ShortArray, sampleRate: Int = 16000) {
        check(!closed && result == null) { "Recording already finished" }
        require(sampleRate == 16000 && pcm.size <= 240000 - samples) { "PTT audio exceeds 15 seconds or has wrong sample rate" }
        if (pcm.isEmpty()) return
        timed { decoder.accept(FloatArray(pcm.size) { pcm[it] / 32768f }) }
        samples += pcm.size
        if (samples - lastDecode >= decodeEverySamples) {
            val before = inferenceNs
            timed { update(decoder.decode(false)) }
            // Avoid repeatedly decoding faster than this phone can keep up.
            decodeEverySamples = ((inferenceNs - before) / 1e9 * 16000).toInt().coerceIn(8000, 80000)
            lastDecode = samples
        }
    }
    fun partialText(): String = UnicodeText.normalize(lines.values.joinToString(" "))
    fun finish(): RecognitionResult {
        result?.let { return it }
        check(!closed) { "Recording discarded" }
        try {
            timed { decoder.stop(); update(decoder.decode(true)) }
            return RecognitionResult(partialText(), samples / 16000.0, inferenceNs / 1e6).also { result = it }
        } finally { close() }
    }
    private fun update(updates: List<StreamingText>) { updates.forEach { lines[it.id] = it.text } }
    private inline fun timed(block: () -> Unit) {
        val start = nanoTime()
        try { block() } finally { inferenceNs += nanoTime() - start }
    }
    override fun close() { if (!closed) { closed = true; decoder.close() } }
}
