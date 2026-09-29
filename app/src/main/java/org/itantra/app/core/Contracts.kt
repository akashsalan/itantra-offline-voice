package org.itantra.app.core

import java.io.File
import java.text.Normalizer

enum class LanguageCode(val code: String, val displayName: String, val voice: String = code) {
    BN("bn", "Bengali"), EN("en", "English", "en-us"), GU("gu", "Gujarati"), HI("hi", "Hindi"),
    KN("kn", "Kannada"), ML("ml", "Malayalam"), MR("mr", "Marathi"), OR("or", "Odia"),
    TA("ta", "Tamil"), TE("te", "Telugu");
    companion object { fun fromCode(code: String) = entries.single { it.code == code } }
}
data class AsrPack(val language: LanguageCode, val engine: String, val directory: File, val bytes: Long, val packId: String)
data class LoadResult(val elapsedMs: Double)
data class RecognitionResult(val text: String, val audioSeconds: Double, val inferenceMs: Double)
interface SpeechRecognizerEngine : AutoCloseable {
    /** Only engines with a bounded live consumer receive PCM during capture. */
    val acceptsLivePcm: Boolean get() = false
    suspend fun load(pack: AsrPack): LoadResult
    fun acceptPcm16(samples: ShortArray, sampleRate: Int = 16_000)
    fun partialText(): String?
    suspend fun finish(): RecognitionResult
    fun reset()
}
data class TtsRequest(val text: String, val language: LanguageCode, val emergency: Boolean = false, val rate: Int = 165, val pitch: Int = 50)
data class PcmAudio(val samples: ShortArray, val sampleRate: Int, val synthesisMs: Double, val firstPcmMs: Double?,
    val firstPcmElapsedNs: Long? = null)
interface SpeechSynthesizerEngine : AutoCloseable {
    suspend fun prepare() {} // Optional idle initialization; never synthesizes or opens the microphone.
    fun supports(language: LanguageCode): Boolean
    suspend fun synthesize(request: TtsRequest): PcmAudio
    fun stop()
}
object UnicodeText {
    fun normalize(text: String): String = Normalizer.normalize(text.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
    fun chunks(text: String, limit: Int = 2048): List<String> {
        require(limit >= 4)
        val normalized = normalize(text)
        val out = mutableListOf<String>()
        val part = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            require(codePoint !in 0xD800..0xDFFF) { "Unpaired Unicode surrogate" }
            val value = String(Character.toChars(codePoint))
            val count = value.toByteArray(Charsets.UTF_8).size
            if (bytes + count > limit) { out += part.toString(); part.setLength(0); bytes = 0 }
            part.append(value); bytes += count; offset += Character.charCount(codePoint)
        }
        if (part.isNotEmpty()) out += part.toString()
        return out
    }
}
