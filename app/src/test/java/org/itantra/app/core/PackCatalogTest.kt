package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class PackCatalogTest {
    private val version = "a".repeat(32)
    @Test fun oldEnglishPointerKeepsLegacyModel() {
        assertEquals(PackCatalog.englishLegacy, PackCatalog.parsePointer(LanguageCode.EN, version).profile)
    }
    @Test fun oldHindiPointerIsUnchanged() {
        assertEquals(PackCatalog.hindi, PackCatalog.parsePointer(LanguageCode.HI, version).profile)
    }
    @Test fun newPointerRoundTrips() {
        val active = ActivePack(PackCatalog.english, version)
        assertEquals(active, PackCatalog.parsePointer(LanguageCode.EN, active.pointerText()))
    }
    @Test fun defaultsAreEnglishUpdateAndSameHindi() {
        assertEquals(PackCatalog.englishMoonshineSmall, PackCatalog.preferred(LanguageCode.EN))
        assertEquals(PackCatalog.hindi, PackCatalog.preferred(LanguageCode.HI))
        assertNotEquals(PackCatalog.english.sourcePrefix, PackCatalog.englishLegacy.sourcePrefix)
    }
    @Test fun smallStreamingIsPreferredAndParakeetIsRecoverable() {
        assertEquals(listOf(PackCatalog.englishParakeet, PackCatalog.englishMoonshineSmall, PackCatalog.englishMoonshineTiny), PackCatalog.profiles.filter { it.language == LanguageCode.EN && PackCatalog.supported(it) })
        assertEquals("English", PackCatalog.englishParakeet.label)
        assertEquals(AsrEngine.ZIPFORMER, PackCatalog.english.engine)
        assertEquals(AsrEngine.NEMO_CTC, PackCatalog.englishParakeet.engine)
        assertEquals(AsrEngine.MOONSHINE, PackCatalog.englishMoonshine.engine)
        assertEquals(AsrEngine.MOONSHINE_STREAMING, PackCatalog.englishMoonshineSmall.engine)
        assertEquals(PackCatalog.englishMoonshineSmall, PackCatalog.preferred(LanguageCode.EN))
    }
    @Test fun everyEnglishChoiceCanPersistWithoutChangingLanguage() {
        PackCatalog.profiles.filter { it.language == LanguageCode.EN }.forEach { profile ->
            assertEquals(LanguageCode.EN, profile.language)
            assertEquals(profile, PackCatalog.parsePointer(LanguageCode.EN, ActivePack(profile, version).pointerText()).profile)
        }
    }
    @Test fun nonEnglishEngineSelectionsAreUnchanged() {
        PackCatalog.profiles.filter { it.language != LanguageCode.EN }.forEach {
            assertEquals(AsrEngine.NEMO_CTC, it.engine)
        }
    }
    @Test fun lowEndOptionIsEnglishWithSeparateIdentityAndEngine() {
        val tiny = PackCatalog.englishMoonshineTiny
        assertEquals(LanguageCode.EN, tiny.language)
        assertEquals("en", tiny.language.code)
        assertEquals("en-us", tiny.language.voice)
        assertEquals("English — low-end devices", tiny.label)
        assertEquals(AsrEngine.MOONSHINE_TINY_STREAMING, tiny.engine)
        assertNotEquals(PackCatalog.englishMoonshineSmall.sourcePrefix, tiny.sourcePrefix)
        assertEquals(listOf(PackCatalog.englishMoonshineSmall, tiny), PackCatalog.choices(LanguageCode.EN))
    }
    @Test fun preloadedDoesNotOverwriteEitherEnglishChoice() {
        PackCatalog.englishChoices.forEach { selected ->
            PackCatalog.englishChoices.forEach { bundled -> assertFalse(PackCatalog.activateBundled(bundled, selected.id)) }
        }
        assertTrue(PackCatalog.activateBundled(PackCatalog.englishMoonshineSmall, null))
        assertTrue(PackCatalog.activateBundled(PackCatalog.englishMoonshineSmall, PackCatalog.englishParakeet.id))
        assertEquals(PackCatalog.englishMoonshineSmall, PackCatalog.preferred(LanguageCode.EN))
    }
    @Test fun odiaIsExplicitlyExperimentalAndExcludesOriginalCheckpointDirectory() {
        val profile = PackCatalog.preferred(LanguageCode.OR)
        assertTrue(profile.experimental)
        assertEquals(PackCatalog.odia, profile)
        assertEquals("models/source/asr/or-ctc-experimental/", profile.sourcePrefix)
        assertFalse("models/source/asr/or/source-model.nemo".startsWith(profile.sourcePrefix))
        assertEquals(profile, PackCatalog.parsePointer(LanguageCode.OR, ActivePack(profile, version).pointerText()).profile)
        assertFalse(PackCatalog.english.experimental)
        assertFalse(PackCatalog.hindi.experimental)
    }
    @Test(expected = IllegalArgumentException::class) fun languageCannotSelectOtherPack() {
        PackCatalog.resolve(LanguageCode.HI, PackCatalog.english.id)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() {
        PackCatalog.parsePointer(LanguageCode.EN, "../$version")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsExtraComponents() {
        PackCatalog.parsePointer(LanguageCode.EN, "${PackCatalog.english.id}/$version/extra")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnpinnedPack() {
        PackCatalog.resolve(LanguageCode.EN, "asr.untrusted.en")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedVersion() {
        PackCatalog.parsePointer(LanguageCode.EN, "${PackCatalog.english.id}/bad")
    }
}
