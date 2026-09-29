package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class HandsFreePresentationTest {
    private val ready = HandsFreePresentation(connected = true, micGranted = true, modelReady = true)

    @Test fun readyIsNotAlreadyListening() {
        assertTrue(ready.canStart)
        assertEquals(HandsFreeStage.READY, ready.stage)
        assertEquals("Microphone off", ready.microphoneLabel)
    }

    @Test fun startRequiresEveryPrerequisiteAndNeverStartsAnExistingSession() {
        for (mask in 0 until 1024) {
            val ui = HandsFreePresentation(active = mask and 1 != 0, connected = mask and 2 != 0,
                micGranted = mask and 4 != 0, modelReady = mask and 8 != 0,
                busy = mask and 16 != 0, recording = mask and 32 != 0,
                playing = mask and 64 != 0, hasDraft = mask and 128 != 0,
                awaitingRelease = mask and 256 != 0, muted = mask and 512 != 0)
            assertEquals("mask=$mask", mask == 14 || mask == 526, ui.canStart)
        }
    }

    @Test fun missingConnectionHasAnActionableState() {
        assertEquals(HandsFreeStage.CONNECT, ready.copy(connected = false).stage)
    }

    @Test fun permissionIsNotConfusedWithStartingTheMicrophone() {
        assertEquals(HandsFreeStage.MICROPHONE, ready.copy(micGranted = false).stage)
        assertEquals("Microphone off", ready.copy(micGranted = false).microphoneLabel)
    }

    @Test fun missingModelAndModelLoadingAreDifferent() {
        assertEquals(HandsFreeStage.MODEL, ready.copy(modelReady = false).stage)
        assertEquals(HandsFreeStage.BUSY, ready.copy(modelReady = false, busy = true).stage)
    }

    @Test fun existingDraftRequiresReviewNotAutomaticSending() {
        assertEquals(HandsFreeStage.DRAFT, ready.copy(hasDraft = true).stage)
        assertFalse(ready.copy(hasDraft = true).canStart)
    }

    @Test fun listeningOnlyAppearsWhileCaptureIsActuallyRunning() {
        assertEquals(HandsFreeStage.RESUMING, ready.copy(active = true).stage)
        assertEquals(HandsFreeStage.LISTENING, ready.copy(active = true, busy = true, recording = true).stage)
    }

    @Test fun finalRecognitionNeverInvitesUserToKeepSpeaking() {
        val ui = ready.copy(active = true, busy = true)
        assertEquals(HandsFreeStage.PROCESSING, ui.stage)
        assertEquals("Microphone paused", ui.microphoneLabel)
    }

    @Test fun muteOverridesAnInFlightCaptureOrRecognition() {
        val ui = ready.copy(active = true, muted = true, busy = true, recording = true)
        assertEquals(HandsFreeStage.MUTED, ui.stage)
        assertEquals("Microphone muted", ui.microphoneLabel)
    }

    @Test fun replyPlaybackRemainsVisibleWhileOutgoingSpeechIsMuted() {
        val ui = ready.copy(active = true, muted = true, playing = true, incomingPlayback = true)
        assertEquals("Reply playing", ui.title)
        assertEquals("Microphone muted", ui.microphoneLabel)
    }

    @Test fun localReplayIsNotLabelledAsAPeerReply() {
        assertEquals("Playing speech", ready.copy(playing = true).title)
    }

    @Test fun emergencyHasPriorityEvenOutsideAnActiveHandsFreeSession() {
        assertEquals(HandsFreeStage.ALERT, ready.copy(playing = true, emergencyPlayback = true).stage)
    }

    @Test fun endingDoesNotFalselyClaimTheNativeMicrophoneAlreadyStopped() {
        val ui = ready.copy(recording = true, busy = true)
        assertEquals(HandsFreeStage.BUSY, ui.stage)
        assertEquals("Microphone stopping", ui.microphoneLabel)
    }

    @Test fun heldPttNeedsAReleaseBeforeHandsFreeCanStart() {
        assertEquals(HandsFreeStage.RELEASE, ready.copy(awaitingRelease = true).stage)
        assertFalse(ready.copy(awaitingRelease = true).canStart)
    }
}
