package org.itantra.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The split acoustic topology expands encoder features by predicted durations
 * between the two graphs. This mirrors the exporter's reference implementation
 * (`np.repeat`) so a mismatch here would produce wrong-length or garbled speech.
 */
class DurationExpansionTest {

    /** Same algorithm as FastPitchHifiGanEngine.expandByDuration. */
    private fun expand(features: FloatArray, channels: Int, tokens: Int, durations: IntArray): Pair<FloatArray, Int> {
        var frames = 0
        for (index in 0 until tokens) {
            val count = durations[index]
            if (count > 0) frames += count
        }
        require(frames > 0)
        val out = FloatArray(channels * frames)
        for (channel in 0 until channels) {
            val source = channel * tokens
            var target = channel * frames
            for (index in 0 until tokens) {
                val value = features[source + index]
                var remaining = durations[index]
                while (remaining > 0) {
                    out[target++] = value
                    remaining--
                }
            }
        }
        return out to frames
    }

    @Test fun `repeats each column by its duration`() {
        // 2 channels x 4 tokens, row-major: [a b c d | e f g h]
        val features = floatArrayOf(1f, 2f, 3f, 4f, 10f, 20f, 30f, 40f)
        val (out, frames) = expand(features, channels = 2, tokens = 4, durations = intArrayOf(1, 3, 2, 1))
        assertEquals(7, frames)
        // Matches the documented example: [a, b, b, b, c, c, d]
        assertArrayEquals(
            floatArrayOf(1f, 2f, 2f, 2f, 3f, 3f, 4f, 10f, 20f, 20f, 20f, 30f, 30f, 40f),
            out, 0f
        )
    }

    @Test fun `zero and negative durations drop their column`() {
        val features = floatArrayOf(1f, 2f, 3f)
        val (out, frames) = expand(features, channels = 1, tokens = 3, durations = intArrayOf(2, 0, -5))
        assertEquals(2, frames)
        assertArrayEquals(floatArrayOf(1f, 1f), out, 0f)
    }

    @Test fun `frame count is the sum of durations`() {
        val durations = intArrayOf(4, 1, 7, 2, 3)
        val features = FloatArray(durations.size) { it.toFloat() }
        val (_, frames) = expand(features, channels = 1, tokens = durations.size, durations = durations)
        assertEquals(durations.sum(), frames)
    }

    @Test fun `channel layout stays row major after expansion`() {
        val channels = 3
        val tokens = 2
        val features = FloatArray(channels * tokens) { it.toFloat() }
        val (out, frames) = expand(features, channels, tokens, intArrayOf(2, 1))
        assertEquals(3, frames)
        for (channel in 0 until channels) {
            // Each channel's block must start with its own first token value.
            assertEquals(features[channel * tokens], out[channel * frames], 0f)
        }
    }
}
