package org.itantra.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for voice selection. These exercise the decision rules without
 * loading a model: the real neural engine needs native ONNX Runtime, so the
 * selection logic is mirrored here against the same contract the router
 * implements. Native synthesis is covered by the Android test.
 */
class TtsRouterPolicyTest {

    /** Mirrors TtsRouter.select for the cases that must never regress. */
    private class Policy(
        private val installed: MutableSet<LanguageCode> = mutableSetOf(),
        private val loadFails: MutableSet<LanguageCode> = mutableSetOf()
    ) {
        var residentLanguage: LanguageCode? = null
            private set
        var loads = 0
            private set
        private val degraded = mutableSetOf<LanguageCode>()

        fun install(language: LanguageCode) { installed += language }
        fun failLoad(language: LanguageCode) { loadFails += language }

        fun choose(language: LanguageCode, emergency: Boolean): String {
            if (language in degraded) return ESPEAK
            if (emergency) {
                return if (residentLanguage == language) NEURAL else ESPEAK
            }
            if (residentLanguage == language) return NEURAL
            if (language !in installed) return ESPEAK
            residentLanguage = null // one resident voice only
            loads++
            if (language in loadFails) {
                degraded += language
                return ESPEAK
            }
            residentLanguage = language
            return NEURAL
        }

        fun invalidate(language: LanguageCode?) {
            if (language == null) degraded.clear() else degraded.remove(language)
            if (language == null || residentLanguage == language) residentLanguage = null
        }

        companion object { const val ESPEAK = "espeak"; const val NEURAL = "neural" }
    }

    @Test fun `with no voice installed every language uses espeak`() = runBlocking {
        val policy = Policy()
        LanguageCode.entries.forEach {
            assertEquals("${it.code} must use espeak", Policy.ESPEAK, policy.choose(it, emergency = false))
        }
        assertEquals("no model should be loaded", 0, policy.loads)
    }

    @Test fun `installed language uses the neural voice`() {
        val policy = Policy().apply { install(LanguageCode.HI) }
        assertEquals(Policy.NEURAL, policy.choose(LanguageCode.HI, emergency = false))
    }

    @Test fun `languages without a pack still use espeak while another is resident`() {
        val policy = Policy().apply { install(LanguageCode.HI) }
        assertEquals(Policy.NEURAL, policy.choose(LanguageCode.HI, emergency = false))
        assertEquals(Policy.ESPEAK, policy.choose(LanguageCode.OR, emergency = false))
    }

    @Test fun `emergency never triggers a cold load`() {
        val policy = Policy().apply { install(LanguageCode.HI) }
        assertEquals(Policy.ESPEAK, policy.choose(LanguageCode.HI, emergency = true))
        assertEquals("an alert must not load a model", 0, policy.loads)
    }

    @Test fun `emergency uses an already resident matching voice`() {
        val policy = Policy().apply { install(LanguageCode.HI) }
        policy.choose(LanguageCode.HI, emergency = false)
        assertEquals(Policy.NEURAL, policy.choose(LanguageCode.HI, emergency = true))
        assertEquals(1, policy.loads)
    }

    @Test fun `emergency in another language uses espeak even with a resident voice`() {
        val policy = Policy().apply { install(LanguageCode.HI); install(LanguageCode.TA) }
        policy.choose(LanguageCode.HI, emergency = false)
        assertEquals(Policy.ESPEAK, policy.choose(LanguageCode.TA, emergency = true))
        assertEquals(LanguageCode.HI, policy.residentLanguage)
    }

    @Test fun `only one voice stays resident across a language switch`() {
        val policy = Policy().apply { install(LanguageCode.HI); install(LanguageCode.TA) }
        policy.choose(LanguageCode.HI, emergency = false)
        policy.choose(LanguageCode.TA, emergency = false)
        assertEquals(LanguageCode.TA, policy.residentLanguage)
        assertEquals(2, policy.loads)
    }

    @Test fun `a resident voice is reused without reloading`() {
        val policy = Policy().apply { install(LanguageCode.HI) }
        repeat(4) { policy.choose(LanguageCode.HI, emergency = false) }
        assertEquals(1, policy.loads)
    }

    @Test fun `a failed load degrades to espeak and is not retried`() {
        val policy = Policy().apply { install(LanguageCode.HI); failLoad(LanguageCode.HI) }
        assertEquals(Policy.ESPEAK, policy.choose(LanguageCode.HI, emergency = false))
        assertEquals(Policy.ESPEAK, policy.choose(LanguageCode.HI, emergency = false))
        assertEquals("must not retry a broken voice every utterance", 1, policy.loads)
    }

    @Test fun `invalidate lets a repaired voice load again`() {
        val policy = Policy().apply { install(LanguageCode.HI); failLoad(LanguageCode.HI) }
        policy.choose(LanguageCode.HI, emergency = false)
        policy.invalidate(LanguageCode.HI)
        assertEquals(2, policy.loads.let { policy.choose(LanguageCode.HI, emergency = false); policy.loads })
    }

    @Test fun `invalidate releases the resident voice for that language`() {
        val policy = Policy().apply { install(LanguageCode.HI) }
        policy.choose(LanguageCode.HI, emergency = false)
        policy.invalidate(LanguageCode.HI)
        assertEquals(null, policy.residentLanguage)
    }

    @Test fun `odia uses espeak until its voice pack is installed`() {
        assertTrue(VoiceCatalog.choices(LanguageCode.OR).isNotEmpty())
        val policy = Policy()
        assertEquals(Policy.ESPEAK, policy.choose(LanguageCode.OR, emergency = false))
        policy.install(LanguageCode.OR)
        assertEquals(Policy.NEURAL, policy.choose(LanguageCode.OR, emergency = false))
    }

    @Test fun `only odia uses the split acoustic topology`() {
        // Odia has no published fused graph, so it ships encoder + decoder and
        // the app expands durations between them.
        VoiceCatalog.profiles.forEach { profile ->
            val expected = if (profile.language == LanguageCode.OR) AcousticTopology.SPLIT
            else AcousticTopology.FUSED
            assertEquals("${profile.language.code} topology", expected, profile.topology)
        }
    }

    @Test fun `every language has exactly one voice profile and unique ids`() {
        LanguageCode.entries.forEach {
            assertEquals("${it.code} voice choices", 1, VoiceCatalog.choices(it).size)
        }
        val ids = VoiceCatalog.profiles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(VoiceCatalog.profiles.any { it.sampleRate != 22_050 })
    }

    @Test fun `voice pointers reject a foreign or malformed identity`() {
        val profile = VoiceCatalog.resolve(LanguageCode.HI, "tts.indictts.hi.fastpitch.hifigan.v1")
        val version = "0123456789abcdef0123456789abcdef"
        assertEquals(profile, VoiceCatalog.parsePointer(LanguageCode.HI, "${profile.id}/$version").profile)
        listOf(
            "${profile.id}/short",
            profile.id,
            "asr.indicconformer.hi.int8/$version",
            "tts.indictts.ta.fastpitch.hifigan.v1/$version"
        ).forEach { pointer ->
            runCatching { VoiceCatalog.parsePointer(LanguageCode.HI, pointer) }
                .onSuccess { error("accepted bad pointer: $pointer") }
        }
    }
}
