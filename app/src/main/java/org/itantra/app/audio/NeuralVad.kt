package org.itantra.app.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** The pinned Silero v4 INT8 model consumes 512 samples at 16 kHz (32 ms). */
class NeuralVad(assets: AssetManager) : AutoCloseable {
    private val vad = Vad(assets, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = ASSET, windowSize = WINDOW),
        sampleRate = 16000, numThreads = 1, provider = "cpu"))
    fun speech(samples: ShortArray, offset: Int): Boolean {
        val probability = vad.compute(FloatArray(WINDOW) { samples[offset + it] / 32768f })
        check(probability.isFinite() && probability in 0f..1f) { "VAD inference failed" }
        return probability >= 0.5f
    }
    override fun close() = vad.release()
    companion object {
        const val ASSET = "vad/silero_vad.int8.onnx"
        const val WINDOW = 512
    }
}
