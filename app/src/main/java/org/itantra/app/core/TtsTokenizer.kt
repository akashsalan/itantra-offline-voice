package org.itantra.app.core

/**
 * Longest-match tokenizer for the AI4Bharat FastPitch symbol tables.
 *
 * The published contract is "longest match against the symbol table, first id
 * wins on duplicates". The tables are literal script graphemes rather than
 * phonemes, so there is no grapheme-to-phoneme stage and no pronunciation
 * dictionary to port.
 */
class TtsTokenizer(symbols: List<String>) {
    private val ids: Map<String, Int> = buildMap {
        // First occurrence wins: several tables repeat punctuation.
        symbols.forEachIndexed { index, symbol -> putIfAbsent(symbol, index) }
    }
    private val longest = ids.keys.maxOfOrNull { it.length } ?: 1

    val size get() = ids.size

    /**
     * Characters absent from a table are dropped rather than guessed, because a
     * substituted symbol changes pronunciation. Callers normalize first so that
     * common punctuation survives as an equivalent the table does contain.
     */
    fun encode(text: String): TokenizedText {
        val normalized = normalize(text)
        val tokens = ArrayList<Long>(normalized.length)
        val dropped = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            var matched = 0
            var span = minOf(longest, normalized.length - index)
            while (span > 0) {
                val candidate = normalized.substring(index, index + span)
                val id = ids[candidate]
                if (id != null) {
                    tokens.add(id.toLong())
                    matched = span
                    break
                }
                span--
            }
            if (matched == 0) {
                dropped.append(normalized[index])
                index++
            } else {
                index += matched
            }
        }
        return TokenizedText(tokens.toLongArray(), dropped.toString())
    }

    companion object {
        /**
         * Script-specific full stops are absent from the shipped tables and were
         * being dropped silently, losing sentence-final prosody. Map them onto
         * the ASCII period the tables do contain. This never alters the stored
         * or transmitted message, only the TTS input.
         */
        private val equivalents = mapOf(
            '\u0964' to '.', // Devanagari danda, used by hi/mr/bn/gu/or scripts
            '\u0965' to '.', // Devanagari double danda
            '\u2018' to '\'', '\u2019' to '\'',
            '\u201C' to '"', '\u201D' to '"',
            '\u2013' to '-', '\u2014' to '-',
            '\u00A0' to ' '
        )

        fun normalize(text: String): String {
            val folded = StringBuilder(text.length)
            for (character in UnicodeText.normalize(text)) {
                folded.append(equivalents[character] ?: character)
            }
            return folded.toString()
        }
    }
}

data class TokenizedText(val tokens: LongArray, val dropped: String) {
    val isEmpty get() = tokens.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizedText) return false
        return tokens.contentEquals(other.tokens) && dropped == other.dropped
    }

    override fun hashCode(): Int = 31 * tokens.contentHashCode() + dropped.hashCode()
}
