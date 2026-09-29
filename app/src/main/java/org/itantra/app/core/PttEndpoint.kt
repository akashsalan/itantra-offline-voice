package org.itantra.app.core

enum class FinishReason { MANUAL_RELEASE, TRAILING_SILENCE, MAX_DURATION, DISCARDED }

data class EndpointState(
    val held: Boolean = false, val active: Boolean = false, val speechDetected: Boolean = false,
    val trailingSilenceMs: Long = 0, val reason: FinishReason? = null, val endedNs: Long? = null
)

/** One press owns one endpoint. Only release unlocks the next press. Times use one local clock. */
class PttEndpoint {
    private var state = EndpointState()
    private var speechRunMs = 0L
    private var autoFinish = true
    @Synchronized fun snapshot() = state
    @Synchronized fun press(finishAfterPause: Boolean): Boolean {
        if (state.held || state.active) return false
        state = EndpointState(held = true, active = true)
        speechRunMs = 0
        autoFinish = finishAfterPause
        return true
    }
    // Input is a neural speech classification, never microphone amplitude.
    @Synchronized fun accept(speech: Boolean, durationMs: Long, nowNs: Long): Boolean {
        if (!state.active) return false
        require(durationMs > 0)
        speechRunMs = if (speech) speechRunMs + durationMs else 0
        state = state.copy(speechDetected = state.speechDetected || speechRunMs >= 160,
            trailingSilenceMs = if (speech || !state.speechDetected) 0 else state.trailingSilenceMs + durationMs)
        return autoFinish && state.speechDetected && state.trailingSilenceMs >= 2000 &&
            finish(FinishReason.TRAILING_SILENCE, nowNs)
    }
    @Synchronized fun finish(reason: FinishReason, nowNs: Long): Boolean {
        if (!state.active) return false
        state = state.copy(active = false, reason = reason, endedNs = nowNs)
        return true
    }
    @Synchronized fun release(nowNs: Long): Boolean {
        state = state.copy(held = false)
        return finish(FinishReason.MANUAL_RELEASE, nowNs)
    }
}
