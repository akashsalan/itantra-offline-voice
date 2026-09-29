package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class UnicodeTextTest {
    @Test fun preservesNativeScriptAndNormalizesWhitespace() {
        assertEquals("मुख्य सड़क बंद है।", UnicodeText.normalize("  मुख्य   सड़क\nबंद है। "))
        assertEquals("é", UnicodeText.normalize("e\u0301"))
    }
    @Test fun chunksOnCodePointsAndUtf8Bytes() {
        val text = "मार्ग 🚑 ".repeat(600)
        val expected = UnicodeText.normalize(text)
        val parts = UnicodeText.chunks(text)
        assertTrue(parts.size > 1)
        assertEquals(expected, parts.joinToString(""))
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 2048 })
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedSurrogate() { UnicodeText.chunks("bad\uD800") }
    @Test fun noMessageForSilence() { assertTrue(UnicodeText.chunks(" \n\t").isEmpty()) }
    @Test fun exactCanonicalLanguageRegistry() {
        assertEquals(listOf("bn", "en", "gu", "hi", "kn", "ml", "mr", "or", "ta", "te"), LanguageCode.entries.map { it.code })
        assertEquals(LanguageCode.OR, LanguageCode.fromCode("or"))
    }
}
