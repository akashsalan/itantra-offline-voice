package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class SosConfirmationTest {
    @Test fun publicReceivingRestartInvalidatesAnArmedSos() {
        val gate = SosConfirmation()
        assertTrue(gate.arm(SosDraft("ble:public:1", LanguageCode.EN, "Need help"), 0))
        assertNull(gate.consume("ble:public:3", 3000))
        assertNull(gate.consume("ble:public:1", 3001))
    }
    @Test fun privateVoiceCaptureCannotBecomeAPublicBroadcast() {
        val gate = SosConfirmation()
        assertFalse(gate.armVoice(SosDraft("ble:private-team:1", LanguageCode.EN, "Need help", 1), "ble:public:1", 0))
        assertNull(gate.consume("ble:public:1", 3000))
    }
    private val draft = SosDraft("original-audience", LanguageCode.EN, "Medical assistance needed.")
    @Test fun waitsForWindowAndConsumesOnce() {
        val gate = SosConfirmation()
        assertTrue(gate.arm(draft, 100))
        assertNull(gate.consume(draft.audience, 3099))
        assertEquals(draft, gate.consume(draft.audience, 3100))
        assertNull(gate.consume(draft.audience, 9000))
    }
    @Test fun cancellationNeverTransmits() {
        val gate = SosConfirmation(); gate.arm(draft, 0); gate.cancel()
        assertNull(gate.consume(draft.audience, 9999))
    }
    @Test fun changingAudienceCancelsInsteadOfRetargeting() {
        val gate = SosConfirmation(); gate.arm(draft, 0)
        assertNull(gate.consume("another-group", 3000))
        assertNull(gate.consume(draft.audience, 3001))
    }
    @Test fun blankOrDisconnectedCannotArm() {
        val gate = SosConfirmation()
        assertFalse(gate.arm(draft.copy(text = "  ...  "), 0))
        assertFalse(gate.arm(draft.copy(audience = ""), 0))
    }
    @Test fun completedVoiceHasOneWindowEvenAfterFingerRelease() {
        val gate = SosConfirmation()
        val voice = draft.copy(captureId = 42)
        assertTrue(gate.armVoice(voice, draft.audience, 100))
        assertNull(gate.consume(draft.audience, 3099))
        assertEquals(voice, gate.consume(draft.audience, 3100))
        assertFalse(gate.armVoice(voice, draft.audience, 3200))
        assertNull(gate.consume(draft.audience, 9000))
    }
    @Test fun cancelledVoiceDoesNotRearmButANewPressCan() {
        val gate = SosConfirmation()
        assertTrue(gate.armVoice(draft.copy(captureId = 1), draft.audience, 0))
        gate.cancel()
        assertFalse(gate.armVoice(draft.copy(captureId = 1), draft.audience, 100))
        assertTrue(gate.armVoice(draft.copy(captureId = 2), draft.audience, 200))
    }
    @Test fun noRouteOrChangedRecipientsKeepVoiceUnsent() {
        val gate = SosConfirmation()
        assertFalse(gate.armVoice(draft.copy(audience = "", captureId = 1), "", 0))
        assertFalse(gate.armVoice(draft.copy(captureId = 2), "new-group", 0))
        assertFalse(gate.armVoice(draft.copy(text = "...", captureId = 3), draft.audience, 0))
        assertNull(gate.consume(draft.audience, 5000))
    }
}
