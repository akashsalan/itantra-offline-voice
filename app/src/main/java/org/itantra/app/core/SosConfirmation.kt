package org.itantra.app.core

data class SosDraft(val audience: String, val language: LanguageCode, val text: String, val captureId: Long? = null)

/** A cancellation window, not a delivery queue. Consuming transfers ownership exactly once. */
class SosConfirmation(private val cancelWindowMs: Long = 3000) {
    private var pending: SosDraft? = null
    private var deadline = 0L
    private var lastVoiceCapture: Long? = null
    /** A completed PTT may trigger one window only, even after cancellation or finger release. */
    @Synchronized fun armVoice(draft: SosDraft, currentAudience: String, nowMs: Long): Boolean {
        val id = draft.captureId ?: return false
        if (id == lastVoiceCapture) return false
        lastVoiceCapture = id
        if (currentAudience != draft.audience) return false
        return arm(draft, nowMs)
    }
    @Synchronized fun arm(draft: SosDraft, nowMs: Long): Boolean {
        if (pending != null || draft.audience.isBlank() || !draft.text.any { it.isLetterOrDigit() } || draft.text.length > 8000) return false
        pending = draft.copy(text = UnicodeText.normalize(draft.text))
        deadline = nowMs + cancelWindowMs
        return true
    }
    @Synchronized fun remaining(nowMs: Long): Long = if (pending == null) 0 else (deadline - nowMs).coerceAtLeast(0)
    @Synchronized fun cancel() { pending = null }
    @Synchronized fun consume(audience: String, nowMs: Long): SosDraft? {
        val draft = pending ?: return null
        if (audience != draft.audience) { pending = null; return null }
        if (nowMs < deadline) return null
        pending = null
        return draft
    }
}
