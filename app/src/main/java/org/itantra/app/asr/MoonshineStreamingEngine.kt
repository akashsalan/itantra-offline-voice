package org.itantra.app.asr

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import org.itantra.app.core.*

/** Small/Tiny Streaming v0.1.5, CPU only. No upstream microphone or downloader. */
class MoonshineStreamingEngine : SpeechRecognizerEngine {
    override val acceptsLivePcm = true
    private var transcriber = -1
    private var utterance: StreamingUtterance? = null

    override suspend fun load(pack: AsrPack): LoadResult {
        val profile = PackCatalog.resolve(pack.language, pack.packId)
        require(profile in PackCatalog.englishChoices && pack.engine == profile.engine.wireId)
        val architecture = if (profile == PackCatalog.englishMoonshineTiny) JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            else JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
        close()
        val start = System.nanoTime()
        try { JNI.ensureLibraryLoaded() }
        catch (error: LinkageError) { throw IllegalStateException("English speech runtime could not load. Existing models are kept.", error) }
        val options = arrayOf(
            // The existing Silero/PTT endpoint is authoritative, including manual release.
            TranscriberOption("vad_threshold", "0"),
            TranscriberOption("vad_max_segment_duration", "30"),
            TranscriberOption("return_audio_data", "false"),
            TranscriberOption("identify_speakers", "false"),
            TranscriberOption("word_timestamps", "false"),
            TranscriberOption("log_api_calls", "false"),
            TranscriberOption("log_output_text", "false"),
            TranscriberOption("log_ort_run", "false"),
            TranscriberOption("ort_providers", "CPU")
        )
        transcriber = checked(JNI.moonshineLoadTranscriberFromFiles(pack.directory.absolutePath,
            architecture, options))
        return LoadResult((System.nanoTime() - start) / 1e6)
    }
    private fun checked(value: Int): Int {
        check(value >= 0) { "English speech runtime error: ${JNI.moonshineErrorToString(value)}" }
        return value
    }
    private fun newUtterance(): StreamingUtterance {
        check(transcriber >= 0) { "English model is not loaded" }
        val handle = checked(JNI.moonshineCreateStream(transcriber, 0))
        try { checked(JNI.moonshineStartStream(transcriber, handle)) }
        catch (error: Exception) { JNI.moonshineFreeStream(transcriber, handle); throw error }
        return StreamingUtterance(object : StreamingDecoder {
            override fun accept(samples: FloatArray) {
                checked(JNI.moonshineAddAudioToStream(transcriber, handle, samples, 16000, 0))
            }
            override fun decode(final: Boolean): List<StreamingText> {
                val transcript = checkNotNull(JNI.moonshineTranscribeStream(transcriber, handle,
                    if (final) JNI.MOONSHINE_FLAG_FORCE_UPDATE else 0)) { "English transcription failed" }
                return transcript.lines.orEmpty().map { StreamingText(it.id, it.text.orEmpty()) }
            }
            override fun stop() { checked(JNI.moonshineStopStream(transcriber, handle)) }
            override fun close() { JNI.moonshineFreeStream(transcriber, handle) }
        })
    }
    override fun acceptPcm16(samples: ShortArray, sampleRate: Int) {
        val current = utterance ?: newUtterance().also { utterance = it }
        current.accept(samples, sampleRate)
    }
    override fun partialText(): String? = utterance?.partialText()
    override suspend fun finish(): RecognitionResult = utterance?.finish() ?: RecognitionResult("", 0.0, 0.0)
    override fun reset() { utterance?.close(); utterance = null }
    override fun close() {
        reset()
        if (transcriber >= 0) { JNI.moonshineFreeTranscriber(transcriber); transcriber = -1 }
    }
}
