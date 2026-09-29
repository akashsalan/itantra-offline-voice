package org.itantra.app.core

import org.itantra.app.data.MessageEntity

/** Selects one job; AppRuntime holds its single audio mutex until that job actually finishes. */
object PlaybackQueue {
    fun recoveredStatus(status: String) = if (status in listOf("PLAYING", "WAITING_AUDIO")) "INTERRUPTED" else status
    fun next(messages: List<MessageEntity>, manual: Set<String>, autoSpeak: Boolean, lanMode: Boolean, room: String,
        relayActive: Boolean = false, publicRelayActive: Boolean = false, publicAccepted: Set<String> = emptySet()): MessageEntity? =
        messages.filter {
            it.channel == "VOICE" && (it.key in manual ||
                (it.direction == "IN" && it.playback == "PENDING" && !it.humanAcknowledged &&
                    (if (it.roomId == "ble:public") publicRelayActive && it.key in publicAccepted else if (it.roomId.startsWith("ble:")) relayActive else if (it.roomId.isBlank()) !lanMode else lanMode && it.roomId == room) &&
                    ConversationPolicy.canAutoPlay(it.channel, true, autoSpeak || it.emergency)))
        }.minWithOrNull(compareByDescending<MessageEntity> { it.emergency }.thenBy { it.createdAtMs }.thenBy { it.sequence })
}
