package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class PttEndpointTest {
    private fun speaking() = PttEndpoint().also {
        assertTrue(it.press(true))
        assertFalse(it.accept(false, 3000, 1)) // Initial silence cannot finish.
        assertFalse(it.accept(true, 160, 2))
    }
    @Test fun shortPauseDoesNotFinishAndSpeechResetsIt() {
        val ptt = speaking()
        assertFalse(ptt.accept(false, 1999, 3))
        assertFalse(ptt.accept(true, 32, 4))
        assertEquals(0L, ptt.snapshot().trailingSilenceMs)
        assertFalse(ptt.accept(false, 1999, 5))
    }
    @Test fun twoSecondsFinishesExactlyOnce() {
        val ptt = speaking()
        assertTrue(ptt.accept(false, 2000, 3))
        assertFalse(ptt.accept(false, 2000, 4))
        assertFalse(ptt.finish(FinishReason.MAX_DURATION, 5))
        assertEquals(FinishReason.TRAILING_SILENCE, ptt.snapshot().reason)
        assertEquals(3L, ptt.snapshot().endedNs)
    }
    @Test fun manualReleaseFinishesImmediately() {
        val ptt = speaking()
        assertTrue(ptt.release(3))
        assertEquals(FinishReason.MANUAL_RELEASE, ptt.snapshot().reason)
        assertFalse(ptt.release(4))
        assertTrue(ptt.press(false))
        assertFalse(ptt.accept(true, 160, 5))
        assertFalse(ptt.accept(false, 2500, 6))
        assertTrue(ptt.release(7))
    }
    @Test fun liftAfterAutomaticFinishCannotSendTwiceOrChangeTiming() {
        val ptt = speaking()
        var finalizations = 0
        if (ptt.accept(false, 2000, 3)) finalizations++
        assertFalse(ptt.press(true))
        if (ptt.release(99)) finalizations++
        assertEquals(1, finalizations)
        assertEquals(3L, ptt.snapshot().endedNs)
        assertEquals(FinishReason.TRAILING_SILENCE, ptt.snapshot().reason)
        assertTrue(ptt.press(true))
    }
}
