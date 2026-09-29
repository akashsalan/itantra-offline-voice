package org.itantra.app.core

/** Explicit, process-local consent. Never restore active capture after a restart. */
data class HandsFreeState(
    val active: Boolean = false, val muted: Boolean = false,
    val generation: Long = 0, val audience: String = ""
) {
    fun start(destination: String): HandsFreeState {
        require(destination.isNotBlank()) { "Connect and confirm a conversation first." }
        return HandsFreeState(true, false, generation + 1, destination)
    }
    fun end() = HandsFreeState(generation = generation + 1)
    fun mute(value: Boolean) = copy(muted = value, generation = generation + 1)
    fun canSend(captureGeneration: Long, destination: String) =
        active && !muted && generation == captureGeneration && audience == destination && destination.isNotBlank()
}

data class HandsFreeFrame(val pcm: ShortArray? = null, val finished: Boolean = false)

/** 16 kHz / 512-sample Silero frames. Silence never reaches ASR. Pure and bounded. */
class HandsFreeSegmenter(private val silenceMs: Int = 700) {
    init { require(silenceMs in 500..1200) }
    private val preRoll = ShortArray(3200) // 200 ms, including any tentative speech.
    private var prePosition = 0
    private var preCount = 0
    private val buffer = ShortArray(240000) // One utterance, including pre-roll: at most 15 s.
    private var count = 0
    private var speechRunMs = 0
    var detected = false; private set
    var trailingSilenceMs = 0L; private set
    var reason: FinishReason? = null; private set
    val finished get() = reason != null

    fun accept(frame: ShortArray, speech: Boolean): HandsFreeFrame {
        require(frame.size == 512)
        if (finished) return HandsFreeFrame(finished = true)
        val wasDetected = detected
        if (speech && count == 0) {
            for (i in 0 until preCount) buffer[count++] = preRoll[(prePosition - preCount + i + preRoll.size) % preRoll.size]
        }
        var appended = 0
        if (count > 0 || speech) {
            appended = minOf(frame.size, buffer.size - count)
            frame.copyInto(buffer, count, endIndex = appended)
            count += appended
        }
        speechRunMs = if (speech) speechRunMs + 32 else 0
        detected = detected || speechRunMs >= 250
        trailingSilenceMs = if (speech || !detected) 0 else trailingSilenceMs + 32
        if (!detected && !speech) count = 0
        for (sample in frame) {
            preRoll[prePosition] = sample
            prePosition = (prePosition + 1) % preRoll.size
            preCount = minOf(preCount + 1, preRoll.size)
        }
        if (detected) reason = when {
            count >= buffer.size -> FinishReason.MAX_DURATION
            trailingSilenceMs >= silenceMs -> FinishReason.TRAILING_SILENCE
            else -> null
        }
        val pcm = when {
            !detected -> null
            !wasDetected -> buffer.copyOf(count) // Pre-roll + the entire speech-confirmation window.
            else -> frame.copyOf(appended)
        }
        return HandsFreeFrame(pcm, finished)
    }
    fun samples() = if (detected) buffer.copyOf(count) else ShortArray(0)
}

object EmergencyAudioPolicy {
    fun preempts(emergency: Boolean, activeIncomingEmergency: Boolean, handsFreeCapture: Boolean) =
        !activeIncomingEmergency && (emergency || handsFreeCapture)
    // Bounded recovery, not a guarantee of overriding system audio policy.
    fun retryDelayMs(attempt: Int): Long? = listOf(1000L, 3000L, 10000L).getOrNull(attempt)
}
