package org.itantra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.SpeechSynthesizerEngine
import org.itantra.app.core.TtsRequest
import org.itantra.app.core.VoiceCatalog
import org.itantra.app.models.VoicePacks
import org.itantra.app.tts.EspeakNgEngine
import org.itantra.app.tts.FastPitchHifiGanEngine
import org.itantra.app.tts.OnnxNative
import org.itantra.app.tts.TtsRouter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Device checks for the neural voice path. Measures the real ONNX Runtime bridge
 * and the router's fallback rules. This is not an intelligibility or WER result.
 *
 * Languages without an installed voice pack are skipped rather than failed, so
 * the suite is meaningful on both APK flavours.
 */
@RunWith(AndroidJUnit4::class)
class NeuralVoiceSmokeTest {

    private val samples = mapOf(
        LanguageCode.HI to "मुख्य सड़क बंद है। उत्तर की ओर जाएँ।",
        LanguageCode.EN to "The main road is closed. Move north.",
        LanguageCode.BN to "প্রধান রাস্তা বন্ধ।",
        LanguageCode.GU to "મુખ્ય રસ્તો બંધ છે.",
        LanguageCode.KN to "ಮುಖ್ಯ ರಸ್ತೆ ಮುಚ್ಚಿದೆ.",
        LanguageCode.ML to "പ്രധാന റോഡ് അടച്ചിരിക്കുന്നു.",
        LanguageCode.MR to "मुख्य रस्ता बंद आहे.",
        LanguageCode.TA to "முக்கிய சாலை மூடப்பட்டுள்ளது.",
        LanguageCode.TE to "ప్రధాన రహదారి మూసివేయబడింది."
    )

    @Test fun onnxRuntimeIsReachableFromTheExistingLibrary() {
        // The runtime is the one already shipped in the sherpa-onnx AAR; no
        // second copy is bundled. If this fails, voices degrade to eSpeak.
        assertTrue("ONNX Runtime C API not reachable", OnnxNative.available())
        val version = OnnxNative.runtimeVersion()
        assertTrue("Unexpected runtime version '$version'", version.isNotBlank())
    }

    @Test fun installedVoicesSynthesizeAudibleSpeechAndReportTiming() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packs = VoicePacks(context)
        val measured = JSONObject()
        var checked = 0
        for ((language, text) in samples) {
            val pack = packs.installed(language) ?: continue
            checked++
            val engine = FastPitchHifiGanEngine(pack)
            try {
                val audio = engine.synthesize(TtsRequest(text, language))
                val seconds = audio.samples.size.toDouble() / audio.sampleRate
                assertEquals("Voice sample rate for ${language.code}", 22_050, audio.sampleRate)
                assertTrue("No audible samples for ${language.code}",
                    audio.samples.any { abs(it.toInt()) > 256 })
                assertTrue("Suspiciously short audio for ${language.code}: $seconds s", seconds > 0.3)
                assertNotNull("No first-PCM timing for ${language.code}", audio.firstPcmMs)
                measured.put(language.code, JSONObject()
                    .put("audioSeconds", seconds)
                    .put("synthesisMs", audio.synthesisMs)
                    .put("firstPcmMs", audio.firstPcmMs)
                    .put("rtf", audio.synthesisMs / 1000.0 / seconds))
            } finally { engine.close() }
        }
        if (checked == 0) return@runBlocking // base flavour with no voices imported
        File(context.getExternalFilesDir(null), "neural-voice-android.json")
            .writeText(measured.toString(2))
        assertTrue(checked > 0)
    }

    @Test fun reopeningAVoiceReleasesNativeSessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packs = VoicePacks(context)
        val language = samples.keys.firstOrNull { packs.installed(it) != null } ?: return@runBlocking
        val pack = packs.installed(language)!!
        // Repeated open/close must not leak or crash; a leak shows up as an abort.
        repeat(3) {
            val engine = FastPitchHifiGanEngine(pack)
            try {
                val audio = engine.synthesize(TtsRequest(samples.getValue(language), language))
                assertTrue(audio.samples.isNotEmpty())
            } finally { engine.close() }
        }
    }

    @Test fun routerFallsBackToEspeakForLanguagesWithoutAVoice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packs = VoicePacks(context)
        val espeak = EspeakNgEngine(context)
        val router = TtsRouter(espeak, { packs.installed(it) })
        try {
            // Odia ships no neural pack in this release and must still speak.
            val audio = router.synthesize(TtsRequest("ମୁଖ୍ୟ ରାସ୍ତା ବନ୍ଦ ଅଛି।", LanguageCode.OR))
            assertTrue("Odia produced no audio", audio.samples.any { abs(it.toInt()) > 16 })
            assertTrue(VoiceCatalog.choices(LanguageCode.OR).isNotEmpty())
        } finally { router.close() }
    }

    @Test fun emergencyPlaybackNeverWaitsForAColdNeuralLoad() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packs = VoicePacks(context)
        val language = samples.keys.firstOrNull { packs.installed(it) != null } ?: return@runBlocking
        var lookups = 0
        val espeak = EspeakNgEngine(context)
        val router = TtsRouter(espeak, { lookups++; packs.installed(it) })
        try {
            val audio = router.synthesize(
                TtsRequest(samples.getValue(language), language, emergency = true)
            )
            assertTrue("Alert produced no audio", audio.samples.any { abs(it.toInt()) > 16 })
            assertEquals("An alert must not load a neural voice", 0, lookups)
        } finally { router.close() }
    }

    @Test fun routerIsAStrictPassThroughWhenNoVoiceIsInstalled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val espeak: SpeechSynthesizerEngine = EspeakNgEngine(context)
        val router = TtsRouter(espeak, { null })
        try {
            for (language in LanguageCode.entries) {
                val text = samples[language] ?: "test"
                val audio = router.synthesize(TtsRequest(text, language))
                assertTrue("No audio for ${language.code}", audio.samples.any { abs(it.toInt()) > 16 })
            }
        } finally { router.close() }
    }
}
