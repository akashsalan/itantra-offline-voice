package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class HandsFreeTest {
    private val voice = ShortArray(512) { 120 }
    private val silence = ShortArray(512)

    @Test fun idleSilenceNeverProducesAsrInputOrAnEndpoint() {
        val gate = HandsFreeSegmenter()
        repeat(2000) { assertNull(gate.accept(silence, false).pcm) }
        assertFalse(gate.detected); assertFalse(gate.finished); assertEquals(0, gate.samples().size)
    }
    @Test fun shortNoiseBurstsDoNotConfirmSpeech() {
        val gate = HandsFreeSegmenter()
        repeat(20) { repeat(7) { assertNull(gate.accept(voice, true).pcm) }; gate.accept(silence, false) }
        assertFalse(gate.detected); assertEquals(0, gate.samples().size)
    }
    @Test fun confirmationIncludesPrerollAndDoesNotDropOrDuplicateSamples() {
        val gate = HandsFreeSegmenter()
        repeat(10) { gate.accept(silence, false) }
        val delivered = mutableListOf<Short>()
        repeat(8) { gate.accept(voice, true).pcm?.let { delivered.addAll(it.toList()) } }
        assertEquals(3200 + 8 * 512, delivered.size)
        assertTrue(delivered.take(3200).all { it == 0.toShort() })
        repeat(22) { gate.accept(silence, false).pcm?.let { delivered.addAll(it.toList()) } }
        assertTrue(gate.finished); assertEquals(FinishReason.TRAILING_SILENCE, gate.reason)
        assertArrayEquals(gate.samples(), delivered.toShortArray())
        assertNull(gate.accept(voice, true).pcm) // Exactly one completion per capture.
    }
    @Test fun resumedSpeechResetsTrailingSilence() {
        val gate = HandsFreeSegmenter(500)
        repeat(8) { gate.accept(voice, true) }
        repeat(15) { gate.accept(silence, false) }
        assertFalse(gate.finished)
        gate.accept(voice, true)
        assertEquals(0L, gate.trailingSilenceMs)
        repeat(16) { gate.accept(silence, false) }
        assertTrue(gate.finished)
    }
    @Test fun continuousSpeechIsCappedAtFifteenSeconds() {
        val gate = HandsFreeSegmenter()
        var sent = 0
        repeat(500) { sent += gate.accept(voice, true).pcm?.size ?: 0 }
        assertEquals(FinishReason.MAX_DURATION, gate.reason)
        assertEquals(240000, sent); assertEquals(240000, gate.samples().size)
    }
    @Test fun stoppedMutedOrRetargetedSessionCannotSendLateTranscripts() {
        val active = HandsFreeState().start("wifi:alice")
        val token = active.generation
        assertTrue(active.canSend(token, "wifi:alice"))
        assertFalse(active.canSend(token, "wifi:bob"))
        assertFalse(active.canSend(token, ""))
        assertFalse(active.end().canSend(token, "wifi:alice"))
        assertFalse(active.mute(true).canSend(token, "wifi:alice"))
        assertFalse(active.mute(true).mute(false).canSend(token, "wifi:alice"))
        assertFalse(active.end().start("wifi:alice").canSend(token, "wifi:alice"))
    }
    @Test fun normalMessagesCannotPreemptAnEmergency() {
        assertTrue(EmergencyAudioPolicy.preempts(true, false, false))
        assertTrue(EmergencyAudioPolicy.preempts(false, false, true))
        assertFalse(EmergencyAudioPolicy.preempts(false, false, false))
        assertFalse(EmergencyAudioPolicy.preempts(true, true, true))
    }
    @Test fun audioRecoveryIsBounded() {
        assertEquals(1000L, EmergencyAudioPolicy.retryDelayMs(0))
        assertEquals(10000L, EmergencyAudioPolicy.retryDelayMs(2))
        assertNull(EmergencyAudioPolicy.retryDelayMs(3))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPauseIsRejected() { HandsFreeSegmenter(0) }
    @Test(expected = IllegalArgumentException::class) fun disconnectedHandsFreeCannotStart() { HandsFreeState().start("") }
}
