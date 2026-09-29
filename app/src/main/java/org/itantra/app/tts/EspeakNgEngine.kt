package org.itantra.app.tts

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.itantra.app.core.*
import java.io.File

internal object EspeakNative {
    init { System.loadLibrary("itantra_espeak") }
    external fun initialize(parentPath: String): Int
    external fun synthesize(utf8: ByteArray, voice: String, rate: Int, pitch: Int): ShortArray
    external fun firstPcmLatencyNs(): Long
    external fun stop()
    external fun close()
}
class EspeakNgEngine(private val context: Context) : SpeechSynthesizerEngine {
    private var sampleRate = 0
    private val root = File(context.noBackupFilesDir, "tts-${org.itantra.app.BuildConfig.VERSION_CODE}")
    override fun supports(language: LanguageCode) = language in LanguageCode.entries
    override suspend fun prepare() = withContext(Dispatchers.IO) { initialize() }
    private fun initialize() {
        if (sampleRate > 0) return
        val marker = File(root, ".complete")
        if (!marker.exists()) {
            fun copy(path: String) {
                val children = context.assets.list(path).orEmpty()
                val target = File(root, path)
                if (children.isNotEmpty()) { target.mkdirs(); children.forEach { copy("$path/$it") } }
                else { target.parentFile?.mkdirs(); context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
            }
            copy("espeak-ng-data")
            marker.writeText("bundled-source-build")
        }
        sampleRate = EspeakNative.initialize(root.absolutePath)
    }
    override suspend fun synthesize(request: TtsRequest): PcmAudio = withContext(Dispatchers.IO) {
        require(request.text.isNotBlank() && request.text.toByteArray(Charsets.UTF_8).size <= 2048)
        val start = System.nanoTime()
        initialize()
        val nativeStart = SystemClock.elapsedRealtimeNanos()
        val samples = EspeakNative.synthesize(request.text.toByteArray(Charsets.UTF_8), request.language.voice, if (request.emergency) 145 else request.rate.coerceIn(80, 250), request.pitch.coerceIn(0, 100))
        val first = EspeakNative.firstPcmLatencyNs().takeIf { it >= 0 }
        PcmAudio(samples, sampleRate, (System.nanoTime() - start) / 1e6, first?.div(1e6), first?.let { nativeStart + it })
    }
    override fun stop() { if (sampleRate > 0) EspeakNative.stop() }
    override fun close() { if (sampleRate > 0) { EspeakNative.close(); sampleRate = 0 } }
}
