package org.itantra.app.core

import java.io.File

enum class TtsEngineKind(val wireId: String) {
    ESPEAK("espeak-ng"),
    FASTPITCH_HIFIGAN("fastpitch-hifigan")
}

/**
 * How the acoustic half of a voice is laid out.
 *
 * FUSED is one graph, text to mel, as published for nine languages.
 *
 * SPLIT is two graphs with the duration expansion done in Kotlin between them.
 * FastPitch predicts how long each token lasts, so a fused graph has a
 * data-dependent output length that the ONNX exporters either refuse to trace or
 * silently freeze. Splitting at that boundary makes each graph's output length a
 * function of its own input length, which exports cleanly. Used for Odia, where
 * no fused graph is published and we export it ourselves.
 */
enum class AcousticTopology { FUSED, SPLIT }

/**
 * A neural voice pack profile. Kept in a separate registry from [PackCatalog] so
 * that an ASR pointer can never resolve to a voice pack, or the reverse.
 */
data class VoiceProfile(
    val id: String,
    val language: LanguageCode,
    val label: String,
    val sourcePrefix: String,
    val sampleRate: Int = 22_050,
    val engine: TtsEngineKind = TtsEngineKind.FASTPITCH_HIFIGAN,
    val topology: AcousticTopology = AcousticTopology.FUSED
)

data class ActiveVoice(val profile: VoiceProfile, val version: String) {
    fun pointerText() = "${profile.id}/$version"
}

/** An installed, hash-verified voice pack on disk. */
data class VoicePack(
    val language: LanguageCode,
    val engine: String,
    val directory: File,
    val bytes: Long,
    val packId: String,
    val sampleRate: Int,
    val topology: AcousticTopology = AcousticTopology.FUSED
) {
    val acoustic get() = File(directory, ACOUSTIC)
    val encoder get() = File(directory, ENCODER)
    val decoder get() = File(directory, DECODER)
    val vocoder get() = File(directory, VOCODER)
    val tokens get() = File(directory, TOKENS)

    companion object {
        const val ACOUSTIC = "acoustic.onnx"
        const val ENCODER = "encoder.onnx"
        const val DECODER = "decoder.onnx"
        const val VOCODER = "vocoder.onnx"
        const val TOKENS = "tokens.json"
    }
}

object VoiceCatalog {
    /**
     * AI4Bharat Indic-TTS FastPitch + HiFi-GAN, MIT. The acoustic graph stays
     * int8 (measured RTF about 0.25 on host) while the vocoder is float32
     * because dynamic int8 rewrites every convolution into ConvInteger, which
     * measured 13x slower than the float32 kernels for identical output shape.
     */
    private val indicTts = listOf(
        LanguageCode.BN to "Bengali",
        LanguageCode.EN to "English",
        LanguageCode.GU to "Gujarati",
        LanguageCode.HI to "Hindi",
        LanguageCode.KN to "Kannada",
        LanguageCode.ML to "Malayalam",
        LanguageCode.MR to "Marathi",
        LanguageCode.OR to "Odia",
        LanguageCode.TA to "Tamil",
        LanguageCode.TE to "Telugu"
    )

    /** Odia has no published fused graph, so it ships as two graphs exported here. */
    private val splitLanguages = setOf(LanguageCode.OR)

    val profiles: List<VoiceProfile> = indicTts.map { (language, name) ->
        VoiceProfile(
            id = "tts.indictts.${language.code}.fastpitch.hifigan.v1",
            language = language,
            label = "$name neural voice",
            sourcePrefix = "models/source/tts/indictts/${language.code}/",
            topology = if (language in splitLanguages) AcousticTopology.SPLIT
            else AcousticTopology.FUSED
        )
    }

    fun choices(language: LanguageCode) = profiles.filter { it.language == language }

    fun preferred(language: LanguageCode): VoiceProfile? = choices(language).firstOrNull()

    fun resolve(language: LanguageCode, id: String): VoiceProfile =
        profiles.singleOrNull { it.id == id && it.language == language }
            ?: throw IllegalArgumentException("Unknown voice pack identity or language mismatch")

    fun parsePointer(language: LanguageCode, text: String): ActiveVoice {
        val parts = text.split('/')
        require(parts.size == 2) { "Invalid installed voice pointer" }
        val profile = resolve(language, parts[0])
        val version = parts[1]
        require(version.matches(Regex("[a-f0-9]{32}"))) { "Invalid installed voice version" }
        return ActiveVoice(profile, version)
    }

    /** Languages that ship a bundled voice pack in the preloaded flavour. */
    val bundled = listOf(LanguageCode.EN, LanguageCode.HI)

    fun bundledAsset(language: LanguageCode) = "${language.code}-voice.itpack"
}
