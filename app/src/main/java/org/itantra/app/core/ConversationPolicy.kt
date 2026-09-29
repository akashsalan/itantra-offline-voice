package org.itantra.app.core

/** Speech capture never loops back to local TTS. Playback belongs to received voice messages. */
object ConversationPolicy {
    enum class AfterCapture { EMPTY, REVIEW, SEND }
    fun afterCapture(text: String, autoSend: Boolean, connected: Boolean): AfterCapture = when {
        text.isBlank() -> AfterCapture.EMPTY
        autoSend && connected -> AfterCapture.SEND
        else -> AfterCapture.REVIEW
    }
    fun canAutoPlay(channel: String, incoming: Boolean, enabled: Boolean): Boolean =
        channel == "VOICE" && incoming && enabled
}
