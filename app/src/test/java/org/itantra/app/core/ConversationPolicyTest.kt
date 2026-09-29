package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    @Test fun reviewIsTheDefault() {
        assertEquals(ConversationPolicy.AfterCapture.REVIEW, ConversationPolicy.afterCapture("Hello", false, true))
    }
    @Test fun autoSendRequiresAConfirmedConnection() {
        assertEquals(ConversationPolicy.AfterCapture.REVIEW, ConversationPolicy.afterCapture("Hello", true, false))
        assertEquals(ConversationPolicy.AfterCapture.SEND, ConversationPolicy.afterCapture("Hello", true, true))
    }
    @Test fun silenceIsNeverSent() {
        assertEquals(ConversationPolicy.AfterCapture.EMPTY, ConversationPolicy.afterCapture("  ", true, true))
    }
    @Test fun outgoingSpeechAndTextChatNeverAutoPlay() {
        assertFalse(ConversationPolicy.canAutoPlay("VOICE", false, true))
        assertFalse(ConversationPolicy.canAutoPlay("CHAT", true, true))
    }
    @Test fun receiverControlsVoicePlayback() {
        assertTrue(ConversationPolicy.canAutoPlay("VOICE", true, true))
        assertFalse(ConversationPolicy.canAutoPlay("VOICE", true, false))
    }
}
