package org.itantra.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ForegroundServiceGateTest {
    @Test fun closingOldActivityDoesNotHideItsReplacement() {
        val visibility = ForegroundActivityVisibility()
        val old = Any(); val replacement = Any()
        visibility.update(old, true)
        visibility.update(replacement, true)
        visibility.update(old, false)
        assertTrue(visibility.visible)
        visibility.update(replacement, false)
        assertFalse(visibility.visible)
    }

    @Test fun repeatedStartAndInitialSynchronizationDoNotDoubleCountAnActivity() {
        val visibility = ForegroundActivityVisibility()
        val activity = Any()
        visibility.update(activity, true)
        visibility.update(activity, true)
        visibility.update(activity, false)
        assertFalse(visibility.visible)
    }

    @Test fun duplicateStopOrDisposeCannotHideAnotherVisibleOwner() {
        val visibility = ForegroundActivityVisibility()
        val active = Any(); val old = Any()
        visibility.update(active, true)
        repeat(3) { visibility.update(old, false) }
        assertTrue(visibility.visible)
    }

    @Test fun disconnectedPttKeepsServiceThroughTranscriptionAndCleanup() {
        val recording = ForegroundServiceDemand(captureWork = true, recording = true)
        val transcribing = recording.copy(recording = false)
        assertTrue(recording.needed)
        assertTrue(transcribing.needed)
        assertTrue(transcribing.microphone)
        assertFalse(transcribing.copy(captureWork = false).needed)
    }

    @Test fun mutedHandsFreeRetainsMicrophoneServiceTypeBetweenTurns() {
        val muted = ForegroundServiceDemand(handsFree = true, muted = true)
        assertTrue(muted.needed)
        assertTrue(muted.microphone)
    }

    @Test fun playbackAndLinksDoNotRequireMicrophoneType() {
        for (demand in listOf(ForegroundServiceDemand(playback = true), ForegroundServiceDemand(connected = true))) {
            assertTrue(demand.needed)
            assertFalse(demand.microphone)
        }
    }

    @Test fun requestingServiceDoesNotMeanItIsAlreadyForeground() {
        val gate = ForegroundServiceGate()
        val lease = gate.requestStart()
        assertFalse(lease.ready.isCompleted)
        assertTrue(gate.waitingForPromotion)
    }

    @Test fun promotionReleasesReadinessWaiter() = runBlocking {
        val gate = ForegroundServiceGate()
        val lease = gate.requestStart()
        assertTrue(gate.promoted(gate.created(), lease.id))
        lease.ready.await()
        assertFalse(gate.waitingForPromotion)
    }

    @Test fun updatesWithinOneAudioOperationKeepTheirLease() {
        val gate = ForegroundServiceGate()
        assertSame(gate.requestStart(), gate.requestStart())
    }

    @Test fun intentionalStopNeverBecomesAForegroundFailure() {
        val gate = ForegroundServiceGate()
        val instance = gate.created()
        gate.promoted(instance, gate.requestStart().id)
        gate.requestStop()
        assertEquals(ServiceDestruction.EXPECTED, gate.destroyed(instance))
        assertNull(gate.failure)
    }

    @Test fun oldDestroyAfterNewPressCannotPoisonTheNewLease() = runBlocking {
        val gate = ForegroundServiceGate()
        val oldInstance = gate.created()
        val oldLease = gate.requestStart()
        gate.promoted(oldInstance, oldLease.id)
        gate.requestStop()
        val nextLease = gate.requestStart()
        assertNotEquals(oldLease.id, nextLease.id)
        assertEquals(ServiceDestruction.EXPECTED, gate.destroyed(oldInstance))
        assertSame(nextLease, gate.lease)
        assertTrue(gate.promoted(gate.created(), nextLease.id))
        nextLease.ready.await()
        assertNull(gate.failure)
    }

    @Test fun oldDestroyAfterNewPromotionCannotStopNewOwner() {
        val gate = ForegroundServiceGate()
        val old = gate.created()
        gate.promoted(old, gate.requestStart().id)
        gate.requestStop()
        val next = gate.created()
        gate.promoted(next, gate.requestStart().id)
        assertEquals(ServiceDestruction.EXPECTED, gate.destroyed(old))
        assertEquals(ServiceDestruction.UNEXPECTED, gate.destroyed(next))
    }

    @Test fun stoppedPendingStartCannotPromoteNewlyCreatedService() {
        val gate = ForegroundServiceGate()
        val old = gate.requestStart()
        gate.requestStop()
        val instance = gate.created()
        assertFalse(gate.promoted(instance, old.id))
        assertTrue(old.ready.isCancelled)
    }

    @Test fun oldIntentCannotPromoteOrFailANewLease() {
        val gate = ForegroundServiceGate()
        val old = gate.requestStart()
        gate.requestStop()
        val current = gate.requestStart()
        val instance = gate.created()
        assertFalse(gate.accepts(instance, old.id))
        assertFalse(gate.promoted(instance, old.id))
        assertFalse(current.ready.isCompleted)
        assertTrue(gate.accepts(instance, current.id))
    }

    @Test fun retiringServiceCannotAcknowledgeTheNextLease() {
        val gate = ForegroundServiceGate()
        val old = gate.created()
        gate.promoted(old, gate.requestStart().id)
        gate.requestStop()
        val next = gate.requestStart()
        assertFalse(gate.promoted(old, next.id))
        assertEquals(ServiceDestruction.EXPECTED, gate.destroyed(old))
        assertTrue(gate.waitingForPromotion) // Runtime may reissue this pending explicit request.
    }

    @Test fun unexpectedDeathOfCurrentServiceIsNotSilentlyIgnored() {
        val gate = ForegroundServiceGate()
        val instance = gate.created()
        gate.promoted(instance, gate.requestStart().id)
        assertEquals(ServiceDestruction.UNEXPECTED, gate.destroyed(instance))
    }

    @Test fun duplicateAndUnrelatedDestroyCallbacksAreIgnored() {
        val gate = ForegroundServiceGate()
        val instance = gate.created()
        assertEquals(ServiceDestruction.STALE, gate.destroyed(instance))
        assertEquals(ServiceDestruction.STALE, gate.destroyed(instance))
        assertEquals(ServiceDestruction.STALE, gate.destroyed(999))
    }

    @Test fun serviceFailureReachesWaiterWithTheOriginalCause() = runBlocking {
        val gate = ForegroundServiceGate()
        val lease = gate.requestStart()
        val cause = SecurityException("Fixture permission was revoked")
        assertSame(cause, gate.failed(cause))
        val delivered = runCatching { lease.ready.await() }.exceptionOrNull()
        assertEquals(cause.javaClass, delivered?.javaClass)
        assertEquals(cause.message, delivered?.message)
        // Debug coroutine stack recovery may copy an exception, retaining the original as its cause.
        assertTrue(generateSequence(delivered) { it.cause }.any { it === cause })
        assertSame(cause, runCatching { gate.requestStart() }.exceptionOrNull())
    }

    @Test fun cleanupFailureDoesNotReplaceFirstFailure() {
        val gate = ForegroundServiceGate()
        val cause = IllegalStateException("Original start error")
        gate.failed(cause)
        assertSame(cause, gate.failed(IllegalStateException("Later destroy callback")))
    }

    @Test fun userCanRetryWithoutLeavingTheActivity() {
        val gate = ForegroundServiceGate()
        val old = gate.created()
        gate.promoted(old, gate.requestStart().id)
        gate.failed(SecurityException("Fixture failure"))
        gate.allowRetry()
        assertNull(gate.lease) // Clearing a failure never starts capture/service itself.
        val next = gate.requestStart()
        assertEquals(ServiceDestruction.EXPECTED, gate.destroyed(old))
        assertTrue(gate.promoted(gate.created(), next.id))
    }

    @Test fun stopAfterFailureRetainsReasonUntilExplicitRetry() {
        val gate = ForegroundServiceGate()
        val failure = IllegalStateException("Start timed out")
        gate.failed(failure)
        gate.requestStop()
        assertSame(failure, gate.failure)
    }

    @Test fun microphoneAddedToExistingLinkNeedsANewForegroundAcknowledgement() {
        val gate = ForegroundServiceGate()
        val instance = gate.created()
        val connected = ForegroundServiceDemand(connected = true)
        val linkLease = gate.requestStart(connected.capabilities)
        gate.promoted(instance, linkLease.id)
        val micLease = gate.requestStart(connected.copy(captureWork = true).capabilities)
        assertNotEquals(linkLease.id, micLease.id)
        assertFalse(micLease.ready.isCompleted)
        assertTrue(gate.promoted(instance, micLease.id))
        assertTrue(micLease.ready.isCompleted)
    }

    @Test fun changedCapabilitiesWhileStartingCarryTheOldWaiterForward() = runBlocking {
        val gate = ForegroundServiceGate()
        val instance = gate.created()
        val mic = ForegroundServiceDemand(captureWork = true)
        val first = gate.requestStart(mic.capabilities)
        val latest = gate.requestStart(mic.copy(connected = true).capabilities)
        assertFalse(first.ready.isCompleted)
        assertFalse(gate.promoted(instance, first.id))
        gate.promoted(instance, latest.id)
        first.ready.await()
        latest.ready.await()
    }

    @Test fun capabilityChangeFailureAlsoReachesTheOriginalWaiter() = runBlocking {
        val gate = ForegroundServiceGate()
        val first = gate.requestStart(1)
        gate.requestStart(5)
        gate.failed(IllegalStateException("New service type was denied"))
        assertEquals("New service type was denied", runCatching { first.ready.await() }.exceptionOrNull()?.message)
    }
}
