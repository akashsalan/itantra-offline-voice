package org.itantra.app.core

import org.itantra.app.data.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test fun publicAlertsRequirePublicOptInAndNeverUsePrivateRelayConsent() {
        val alert = message("public").apply { roomId = "ble:public"; emergency = true }
        assertNull(PlaybackQueue.next(listOf(alert), emptySet(), true, false, "", true, false))
        assertEquals("public", PlaybackQueue.next(listOf(alert), emptySet(), false, false, "", false, true, setOf("public"))?.key)
        val private = message("private").apply { roomId = "ble:team"; emergency = true }
        assertNull(PlaybackQueue.next(listOf(private), emptySet(), true, false, "", false, true))
        assertEquals("public", PlaybackQueue.next(listOf(alert), setOf("public"), false, false, "", false, false)?.key)
    }
    @Test fun publicRestartDoesNotReplayRowsFromPreviousConsentEvenBeforeDatabaseCleanup() {
        val old = message("old").apply { roomId = "ble:public"; emergency = true; playback = "PENDING" }
        assertNull(PlaybackQueue.next(listOf(old), emptySet(), true, false, "", false, true, emptySet()))
        val current = message("new").apply { roomId = "ble:public"; emergency = true; playback = "PENDING" }
        assertEquals("new", PlaybackQueue.next(listOf(old, current), emptySet(), true, false, "", false, true, setOf("new"))?.key)
    }
    @Test fun processRestartDoesNotLeaveAnAlertWaitingForADeadRetryJob() {
        assertEquals("INTERRUPTED", PlaybackQueue.recoveredStatus("WAITING_AUDIO"))
        assertEquals("INTERRUPTED", PlaybackQueue.recoveredStatus("PLAYING"))
        assertEquals("PLAYED", PlaybackQueue.recoveredStatus("PLAYED"))
        assertEquals("PENDING", PlaybackQueue.recoveredStatus("PENDING"))
    }
    @Test fun waitingForAudioIsNotPlayedAgainBeforeItsRetryBecomesPending() {
        val alert = message("alert").apply { emergency = true; playback = "WAITING_AUDIO" }
        assertNull(next(listOf(alert)))
        alert.playback = "PENDING"
        assertEquals("alert", next(listOf(alert))?.key)
    }
    private fun message(id: String, time: Long = 1) = MessageEntity().apply {
        key = id; roomId = "room"; direction = "IN"; channel = "VOICE"; playback = "PENDING"; createdAtMs = time
    }
    private fun next(rows: List<MessageEntity>, manual: Set<String> = emptySet(), enabled: Boolean = true) = PlaybackQueue.next(rows, manual, enabled, true, "room")
    @Test fun simultaneousSpeakersAreSelectedOneAtATimeInRoomOrder() {
        val a = message("a").apply { sequence = 1 }; val b = message("b").apply { sequence = 2 }
        assertEquals("a", next(listOf(b, a))?.key)
        a.playback = "PLAYED"
        assertEquals("b", next(listOf(b, a))?.key)
    }
    @Test fun priorityMovesAheadOfWaitingNormalSpeech() {
        val normal = message("normal", 1); val alert = message("alert", 2).apply { emergency = true }
        assertEquals("alert", next(listOf(normal, alert))?.key)
    }
    @Test fun outgoingVoiceRequiresAnExplicitReplay() {
        val own = message("own").apply { direction = "OUT" }
        assertNull(next(listOf(own)))
        assertEquals("own", next(listOf(own), setOf("own"))?.key)
    }
    @Test fun silentDirectMessagesNeverEnterTheSpeechQueue() {
        val chat = message("dm").apply { channel = "CHAT" }
        assertNull(next(listOf(chat), setOf("dm")))
    }
    @Test fun autoPlaySettingAndHumanAcknowledgementAreRespected() {
        val incoming = message("voice")
        assertNull(next(listOf(incoming), enabled = false))
        assertEquals("voice", next(listOf(incoming), setOf("voice"), enabled = false)?.key)
        incoming.humanAcknowledged = true
        assertNull(next(listOf(incoming)))
    }
    @Test fun otherConversationsDoNotAutoPlay() {
        val other = message("other").apply { roomId = "another-room" }
        val legacy = message("legacy").apply { roomId = "" }
        assertNull(next(listOf(other, legacy)))
        assertNull(PlaybackQueue.next(listOf(message("lan")), emptySet(), true, false, "room"))
    }
    @Test fun bleEmergencyRequiresAnActiveRelayOrExplicitReplay() {
        val alert = message("ble").apply { roomId = "ble:team"; emergency = true }
        assertNull(PlaybackQueue.next(listOf(alert), emptySet(), false, false, "", false))
        assertEquals("ble", PlaybackQueue.next(listOf(alert), emptySet(), false, false, "", true)?.key)
        assertEquals("ble", PlaybackQueue.next(listOf(alert), setOf("ble"), false, false, "", false)?.key)
    }
    @Test fun normalAutoPlayOffDoesNotSilenceConfirmedEmergencyRoute() {
        val alert = message("emergency").apply { emergency = true }
        assertEquals("emergency", next(listOf(alert), enabled = false)?.key)
        alert.humanAcknowledged = true
        assertNull(next(listOf(alert), enabled = false))
    }
}
