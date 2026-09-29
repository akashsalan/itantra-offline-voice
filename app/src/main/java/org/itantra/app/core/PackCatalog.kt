package org.itantra.app.core

enum class AsrEngine(val wireId: String) {
    ZIPFORMER("sherpa-online-transducer"), NEMO_CTC("sherpa-offline-nemo-ctc"), MOONSHINE("sherpa-offline-moonshine"),
    MOONSHINE_STREAMING("moonshine-small-streaming"), MOONSHINE_TINY_STREAMING("moonshine-tiny-streaming")
}

data class PackProfile(val id: String, val language: LanguageCode, val sourcePrefix: String, val label: String,
    val experimental: Boolean = false, val engine: AsrEngine = AsrEngine.NEMO_CTC)
data class ActivePack(val profile: PackProfile, val version: String) {
    fun pointerText() = "${profile.id}/$version"
}

/** Only locally pinned catalog entries can select files or activation directories. */
object PackCatalog {
    val englishLegacy = PackProfile("asr.zipformer.en.20m", LanguageCode.EN,
        "models/source/asr/en/", "Zipformer 20M (legacy)", engine = AsrEngine.ZIPFORMER)
    val english = PackProfile("asr.zipformer.en.2023-06-21.int8", LanguageCode.EN,
        "models/source/asr/en-2023-06-21/", "Zipformer (retired)", engine = AsrEngine.ZIPFORMER)
    val englishParakeet = PackProfile("asr.parakeet.en.110m.ctc.int8", LanguageCode.EN,
        "models/source/asr/en-parakeet-ctc/", "English", engine = AsrEngine.NEMO_CTC)
    val englishMoonshine = PackProfile("asr.moonshine.en.base.2026-02-27.quantized", LanguageCode.EN,
        "models/source/asr/en-moonshine-base/", "Moonshine Base", engine = AsrEngine.MOONSHINE)
    val retiredEnglish = listOf(englishLegacy, english, englishMoonshine)
    val englishMoonshineSmall = PackProfile("asr.moonshine.en.small.streaming.2026-08-21.quantized", LanguageCode.EN,
        "models/source/asr/en-moonshine-small-streaming/", "English · Moonshine Small Streaming", engine = AsrEngine.MOONSHINE_STREAMING)
    val englishMoonshineTiny = PackProfile("asr.moonshine.en.tiny.streaming.2026-08-21.quantized", LanguageCode.EN,
        "models/source/asr/en-moonshine-tiny-streaming/", "English — low-end devices", engine = AsrEngine.MOONSHINE_TINY_STREAMING)
    val englishChoices = listOf(englishMoonshineSmall, englishMoonshineTiny)
    fun choices(language: LanguageCode) = if (language == LanguageCode.EN) englishChoices else listOf(preferred(language))
    fun activateBundled(profile: PackProfile, currentId: String?) = currentId == null ||
        (profile == englishMoonshineSmall && currentId == englishParakeet.id)
    // Keep existing Parakeet imports loadable for recovery; do not erase phone models.
    fun supported(profile: PackProfile) = profile.language != LanguageCode.EN || profile in englishChoices || profile == englishParakeet
    val hindi = PackProfile("asr.indicconformer.hi.int8", LanguageCode.HI,
        "models/source/asr/hi/", "Hindi IndicConformer")
    val indic = listOf(LanguageCode.BN, LanguageCode.GU, LanguageCode.KN, LanguageCode.ML, LanguageCode.MR, LanguageCode.TA, LanguageCode.TE).map {
        PackProfile("asr.indicconformer.${it.code}.int8", it, "models/source/asr/${it.code}/", "${it.displayName} IndicConformer")
    }
    val odia = PackProfile("asr.indicconformer.or.ctc.experimental.v1", LanguageCode.OR,
        "models/source/asr/or-ctc-experimental/", "Odia IndicConformer (experimental)", experimental = true)
    val profiles = retiredEnglish + listOf(englishParakeet, englishMoonshineSmall, englishMoonshineTiny, hindi, odia) + indic

    fun preferred(language: LanguageCode): PackProfile = when (language) {
        LanguageCode.EN -> englishMoonshineSmall
        LanguageCode.HI -> hindi
        LanguageCode.OR -> odia
        else -> indic.singleOrNull { it.language == language }
            ?: throw IllegalArgumentException("No compatible pack is available.")
    }
    fun resolve(language: LanguageCode, id: String): PackProfile =
        profiles.singleOrNull { it.id == id && it.language == language }
            ?: throw IllegalArgumentException("Unknown pack identity or language mismatch")

    fun parsePointer(language: LanguageCode, text: String): ActivePack {
        val parts = text.split('/')
        val profile = if (parts.size == 1) {
            // v1 pointers contained only the immutable directory's UUID.
            if (language == LanguageCode.EN) englishLegacy else preferred(language)
        } else {
            require(parts.size == 2) { "Invalid installed pack pointer" }
            resolve(language, parts[0])
        }
        val version = parts.last()
        require(version.matches(Regex("[a-f0-9]{32}"))) { "Invalid installed pack version" }
        return ActivePack(profile, version)
    }
}
