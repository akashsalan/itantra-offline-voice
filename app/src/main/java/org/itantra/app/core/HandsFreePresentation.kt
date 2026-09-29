package org.itantra.app.core

/** Presentation only: these states never start a microphone or send a message. */
enum class HandsFreeStage(val title: String, val detail: String) {
    CONNECT("Choose your conversation", "Connect and confirm a person or group before starting."),
    MICROPHONE("Microphone permission needed", "Allow microphone access. Starting hands-free is a separate step."),
    MODEL("Choose a speech model", "Import or select an offline model for the language you speak."),
    DRAFT("Your draft is waiting", "Review, send or discard it in push-to-talk mode before starting."),
    RELEASE("Release the talk button", "Finish your push-to-talk interaction before starting hands-free."),
    READY("Ready when you are", "Start your microphone to send speech automatically after each pause."),
    LISTENING("Listening", "Your turn. Speak naturally, then pause to send."),
    PROCESSING("Processing your words", "Microphone paused. Wait for Listening before speaking again."),
    PLAYBACK("Playing speech", "Microphone paused while speech is prepared and played."),
    ALERT("Emergency playback", "Priority speech. Use Acknowledge on the alert when received."),
    MUTED("You're muted", "Your speech won't be sent. Incoming voice replies can still play."),
    RESUMING("Getting ready to listen", "Wait for Listening before speaking. Replies may play first."),
    BUSY("Finishing up", "Wait for the current speech task to finish before starting.")
}

data class HandsFreePresentation(
    val active: Boolean = false,
    val muted: Boolean = false,
    val connected: Boolean = false,
    val micGranted: Boolean = false,
    val modelReady: Boolean = false,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val playing: Boolean = false,
    val incomingPlayback: Boolean = false,
    val emergencyPlayback: Boolean = false,
    val hasDraft: Boolean = false,
    val awaitingRelease: Boolean = false
) {
    val canStart get() = !active && connected && micGranted && modelReady &&
        !busy && !recording && !playing && !hasDraft && !awaitingRelease

    val stage get() = when {
        playing && emergencyPlayback -> HandsFreeStage.ALERT
        playing -> HandsFreeStage.PLAYBACK
        !connected -> HandsFreeStage.CONNECT
        active && muted -> HandsFreeStage.MUTED
        !micGranted -> HandsFreeStage.MICROPHONE
        active && recording -> HandsFreeStage.LISTENING
        active && busy -> HandsFreeStage.PROCESSING
        !active && (busy || recording) -> HandsFreeStage.BUSY
        !modelReady -> HandsFreeStage.MODEL
        !active && hasDraft -> HandsFreeStage.DRAFT
        !active && awaitingRelease -> HandsFreeStage.RELEASE
        active -> HandsFreeStage.RESUMING
        else -> HandsFreeStage.READY
    }

    val title get() = if (stage == HandsFreeStage.PLAYBACK && incomingPlayback) "Reply playing" else stage.title
    val microphoneLabel get() = when {
        !active && recording -> "Microphone stopping"
        active && muted -> "Microphone muted"
        active && recording && !playing -> "Microphone on"
        active -> "Microphone paused"
        else -> "Microphone off"
    }
}
