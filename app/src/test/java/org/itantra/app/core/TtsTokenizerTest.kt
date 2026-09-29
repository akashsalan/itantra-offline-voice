package org.itantra.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTokenizerTest {

    private val table = listOf("<PAD>", "a", "b", "c", ".", " ", "kh", "<BLNK>", "a")

    @Test fun `first id wins for duplicate symbols`() {
        // "a" appears at index 1 and index 8; the documented contract is first wins.
        val encoded = TtsTokenizer(table).encode("a")
        assertArrayEquals(longArrayOf(1), encoded.tokens)
    }

    @Test fun `prefers the longest matching symbol`() {
        // "kh" must win over "k" + "h" when the table contains the digraph.
        val encoded = TtsTokenizer(table).encode("kh")
        assertArrayEquals(longArrayOf(6), encoded.tokens)
    }

    @Test fun `falls back to shorter matches when a long one is absent`() {
        val encoded = TtsTokenizer(table).encode("abc")
        assertArrayEquals(longArrayOf(1, 2, 3), encoded.tokens)
    }

    @Test fun `unmapped characters are reported and never guessed`() {
        val encoded = TtsTokenizer(table).encode("aXb")
        assertArrayEquals(longArrayOf(1, 2), encoded.tokens)
        assertEquals("X", encoded.dropped)
    }

    @Test fun `devanagari danda becomes a period so sentence prosody survives`() {
        // U+0964 is absent from every shipped Indic table and was being dropped.
        val encoded = TtsTokenizer(table).encode("ab\u0964")
        assertArrayEquals(longArrayOf(1, 2, 4), encoded.tokens)
        assertEquals("", encoded.dropped)
    }

    @Test fun `double danda and typographic punctuation are folded`() {
        assertEquals("a.", TtsTokenizer.normalize("a\u0965"))
        assertEquals("a-b", TtsTokenizer.normalize("a\u2014b"))
        assertEquals("a b", TtsTokenizer.normalize("a\u00A0b"))
    }

    @Test fun `whitespace is collapsed before tokenizing`() {
        val encoded = TtsTokenizer(table).encode("a   b")
        assertArrayEquals(longArrayOf(1, 5, 2), encoded.tokens)
    }

    @Test fun `blank text yields no tokens rather than an exception`() {
        assertTrue(TtsTokenizer(table).encode("   ").isEmpty)
    }

    @Test fun `table size counts unique symbols`() {
        assertEquals(8, TtsTokenizer(table).size)
    }
}
