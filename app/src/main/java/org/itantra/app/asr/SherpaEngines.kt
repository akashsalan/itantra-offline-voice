package org.itantra.app.asr

import com.k2fsa.sherpa.onnx.*
import org.itantra.app.core.*
import java.io.File

fun speechRecognizerFor(pack: AsrPack): SpeechRecognizerEngine = when (pack.engine) {
    AsrEngine.ZIPFORMER.wireId -> SherpaOnlineTransducerEngine()
    AsrEngine.NEMO_CTC.wireId -> SherpaOfflineNemoCtcEngine()
    AsrEngine.MOONSHINE.wireId -> SherpaOfflineMoonshineEngine()
    AsrEngine.MOONSHINE_STREAMING.wireId, AsrEngine.MOONSHINE_TINY_STREAMING.wireId -> MoonshineStreamingEngine()
    else -> throw IllegalArgumentException("Unsupported ASR engine: ${pack.engine}")
}

// Caller serializes all engine operations on its worker dispatcher.
class SherpaOnlineTransducerEngine : SpeechRecognizerEngine {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var sampleCount = 0L
    private var inferenceNs = 0L
    override suspend fun load(pack: AsrPack): LoadResult {
        close()
        val start = System.nanoTime()
        fun path(name: String) = File(pack.directory, name).absolutePath
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = path("encoder-epoch-99-avg-1.int8.onnx"),
                decoder = path("decoder-epoch-99-avg-1.onnx"),
                joiner = path("joiner-epoch-99-avg-1.int8.onnx")
            ), tokens = path("tokens.txt"), numThreads = 2, provider = "cpu", modelType = "zipformer"
        )
        recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = model, enableEndpoint = false, decodingMethod = "greedy_search"))
        reset()
        return LoadResult((System.nanoTime() - start) / 1e6)
    }
    override fun acceptPcm16(samples: ShortArray, sampleRate: Int) {
        require(sampleRate == 16000)
        sampleCount += samples.size
        val started = System.nanoTime()
        val active = checkNotNull(stream)
        active.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, sampleRate)
        val engine = checkNotNull(recognizer)
        while (engine.isReady(active)) engine.decode(active)
        inferenceNs += System.nanoTime() - started
    }
    override fun partialText(): String? = stream?.let { recognizer?.getResult(it)?.text }
    override suspend fun finish(): RecognitionResult {
        val active = checkNotNull(stream)
        val engine = checkNotNull(recognizer)
        val started = System.nanoTime()
        // sherpa-onnx v1.13.8 online-decode-files.py: 0.66 s flush, not captured speech.
        active.acceptWaveform(FloatArray(10560), 16000)
        active.inputFinished()
        while (engine.isReady(active)) engine.decode(active)
        val text = UnicodeText.normalize(engine.getResult(active).text)
        inferenceNs += System.nanoTime() - started
        return RecognitionResult(text, sampleCount / 16000.0, inferenceNs / 1e6)
    }
    override fun reset() { stream?.release(); stream = recognizer?.createStream(); sampleCount = 0; inferenceNs = 0 }
    override fun close() { stream?.release(); stream = null; recognizer?.release(); recognizer = null }
}

class SherpaOfflineNemoCtcEngine : SherpaOfflineEngine() {
    override fun modelConfig(pack: AsrPack) = OfflineModelConfig(
        nemo = OfflineNemoEncDecCtcModelConfig(model = File(pack.directory, "model.int8.onnx").absolutePath),
        tokens = File(pack.directory, "tokens.txt").absolutePath, numThreads = 2, provider = "cpu")
}

class SherpaOfflineMoonshineEngine : SherpaOfflineEngine() {
    override fun modelConfig(pack: AsrPack) = OfflineModelConfig(
        moonshine = OfflineMoonshineModelConfig(
            encoder = File(pack.directory, "encoder_model.ort").absolutePath,
            mergedDecoder = File(pack.directory, "decoder_model_merged.ort").absolutePath),
        tokens = File(pack.directory, "tokens.txt").absolutePath, numThreads = 2, provider = "cpu")
}

/** Endpoint decoding shares the same bounded PCM path for IndicConformer, Parakeet and Moonshine. */
abstract class SherpaOfflineEngine : SpeechRecognizerEngine {
    protected abstract fun modelConfig(pack: AsrPack): OfflineModelConfig
    private var recognizer: OfflineRecognizer? = null
    private val samples = ArrayList<FloatArray>()
    private var sampleCount = 0
    override suspend fun load(pack: AsrPack): LoadResult {
        close()
        val start = System.nanoTime()
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig(pack),
            decodingMethod = "greedy_search"
        ))
        return LoadResult((System.nanoTime() - start) / 1e6)
    }
    override fun acceptPcm16(samples: ShortArray, sampleRate: Int) {
        require(sampleRate == 16000)
        require(sampleCount + samples.size <= 16000 * 15) { "Utterance exceeds 15 seconds" }
        this.samples += FloatArray(samples.size) { samples[it] / 32768f }; sampleCount += samples.size
    }
    override fun partialText(): String? = null
    override suspend fun finish(): RecognitionResult {
        val started = System.nanoTime()
        val waveform = FloatArray(sampleCount)
        var offset = 0
        samples.forEach { it.copyInto(waveform, offset); offset += it.size }
        val engine = checkNotNull(recognizer)
        val active = engine.createStream()
        try {
            active.acceptWaveform(waveform, 16000)
            engine.decode(active)
            return RecognitionResult(UnicodeText.normalize(engine.getResult(active).text), sampleCount / 16000.0, (System.nanoTime() - started) / 1e6)
        } finally { active.release(); samples.clear() }
    }
    override fun reset() { samples.clear(); sampleCount = 0 }
    override fun close() { reset(); recognizer?.release(); recognizer = null }
}
