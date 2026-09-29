package org.itantra.app.core

import kotlinx.coroutines.CompletableDeferred

/** Activity recreation/multi-window can start a new owner before the old one stops. */
internal class ForegroundActivityVisibility {
    private val visibleOwners = mutableSetOf<Any>()
    val visible get() = visibleOwners.isNotEmpty()
    fun update(owner: Any, visible: Boolean) {
        if (visible) visibleOwners += owner else visibleOwners -= owner
    }
}

/** Capture work includes ASR finalization and native cleanup, not just AudioRecord. */
internal data class ForegroundServiceDemand(
    val captureWork: Boolean = false,
    val recording: Boolean = false,
    val handsFree: Boolean = false,
    val muted: Boolean = false,
    val playback: Boolean = false,
    val connected: Boolean = false
) {
    val microphone get() = captureWork || handsFree
    val needed get() = microphone || playback || connected
    // Internal capabilities, deliberately independent of Android's API-level flags.
    val capabilities get() = (if (microphone) 1 else 0) or (if (playback) 2 else 0) or (if (connected) 4 else 0)
}

internal data class ForegroundServiceLease(val id: Long, val capabilities: Int,
    val ready: CompletableDeferred<Unit> = CompletableDeferred())
internal enum class ServiceDestruction { EXPECTED, STALE, UNEXPECTED }

/** Main-thread-owned lifecycle bookkeeping; no Android objects or automatic capture. */
internal class ForegroundServiceGate {
    private var nextLease = 0L
    private var nextInstance = 0L
    private val instances = mutableSetOf<Long>()
    private val retiring = mutableSetOf<Long>()
    private var owner: Long? = null
    var lease: ForegroundServiceLease? = null; private set
    var failure: Throwable? = null; private set
    val waitingForPromotion get() = lease?.ready?.isCompleted == false

    fun created(): Long = (++nextInstance).also { instances += it }

    fun requestStart(capabilities: Int = 0): ForegroundServiceLease {
        failure?.let { throw it }
        val previous = lease
        if (previous?.capabilities == capabilities) return previous
        val next = ForegroundServiceLease(++nextLease, capabilities)
        if (previous != null && !previous.ready.isCompleted) {
            // A link change while microphone startup is pending must not cancel
            // the caller. Its waiter follows the newest foreground promotion.
            next.ready.invokeOnCompletion { error ->
                if (error == null) previous.ready.complete(Unit)
                else previous.ready.completeExceptionally(error)
            }
        }
        lease = next
        return next
    }

    fun accepts(instance: Long, leaseId: Long) = failure == null && lease?.id == leaseId &&
        instance in instances && instance !in retiring

    fun promoted(instance: Long, leaseId: Long): Boolean {
        if (!accepts(instance, leaseId)) return false
        owner = instance
        lease!!.ready.complete(Unit)
        return true
    }

    fun requestStop() {
        // Mark *instances*, not one global stopping flag. A later press may already
        // have acquired a new lease when Android delivers the old onDestroy().
        retiring += instances
        lease?.ready?.cancel()
        lease = null
        owner = null
    }

    fun destroyed(instance: Long): ServiceDestruction {
        if (!instances.remove(instance)) return ServiceDestruction.STALE
        val expected = retiring.remove(instance)
        val owned = owner == instance
        if (owned) owner = null
        return when {
            expected -> ServiceDestruction.EXPECTED
            owned && lease != null && failure == null -> ServiceDestruction.UNEXPECTED
            else -> ServiceDestruction.STALE
        }
    }

    fun failed(error: Throwable): Throwable {
        val original = failure ?: error
        failure = original
        lease?.ready?.completeExceptionally(original)
        retiring += instances
        lease = null
        owner = null
        return original
    }

    /** Eligibility to retry is not consent to start; requestStart is still explicit. */
    fun allowRetry() { if (lease == null) failure = null }
}
