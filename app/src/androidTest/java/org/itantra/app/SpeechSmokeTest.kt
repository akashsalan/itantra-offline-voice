package org.itantra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.TtsRequest
import org.itantra.app.tts.EspeakNgEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Native output/lifecycle smoke tests, not intelligibility or WER evidence.
@RunWith(AndroidJUnit4::class)
class SpeechSmokeTest {
    @Test fun bundledVoicesProducePcmAndEngineCanReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val examples = mapOf(
            LanguageCode.BN to "প্রধান রাস্তা বন্ধ। উত্তরে যান।",
            LanguageCode.EN to "The main road is closed. Move north.",
            LanguageCode.GU to "મુખ્ય રસ્તો બંધ છે. ઉત્તર તરફ જાઓ.",
            LanguageCode.HI to "मुख्य सड़क बंद है। उत्तर की ओर जाएँ।",
            LanguageCode.KN to "ಮುಖ್ಯ ರಸ್ತೆ ಮುಚ್ಚಿದೆ. ಉತ್ತರಕ್ಕೆ ಹೋಗಿ.",
            LanguageCode.ML to "പ്രധാന റോഡ് അടച്ചിരിക്കുന്നു.",
            LanguageCode.MR to "मुख्य रस्ता बंद आहे. उत्तरेकडे जा.",
            LanguageCode.OR to "ମୁଖ୍ୟ ରାସ୍ତା ବନ୍ଦ ଅଛି।",
            LanguageCode.TA to "முக்கிய சாலை மூடப்பட்டுள்ளது.",
            LanguageCode.TE to "ప్రధాన రహదారి మూసివేయబడింది."
        )
        repeat(2) {
            val engine = EspeakNgEngine(context)
            try {
                examples.forEach { (language, text) ->
                    val audio = engine.synthesize(TtsRequest(text, language))
                    assertTrue("No audible samples for ${language.code}", audio.samples.any { sample -> kotlin.math.abs(sample.toInt()) > 16 })
                    assertTrue(audio.sampleRate > 0 && audio.firstPcmMs != null)
                }
            } finally { engine.close() }
        }
    }
}
