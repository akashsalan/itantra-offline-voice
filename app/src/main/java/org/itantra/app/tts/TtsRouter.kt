package org.itantra.app.tts

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.PcmAudio
import org.itantra.app.core.SpeechSynthesizerEngine
import org.itantra.app.core.TtsRequest
import org.itantra.app.core.VoicePack

/**
 * Chooses between the bundled eSpeak NG voice and an optional neural voice pack.
 *
 * Rules that must not regress:
 *  - With no voice pack installed this is a pass-through to eSpeak, so a fresh
 *    install behaves exactly as it did before neural voices existed.
 *  - Emergency alerts never trigger a cold model load. They use an already
 *    resident neural voice or eSpeak, because alert playback must stay prompt.
 *  - At most one neural voice is resident. Switching language releases the
 *    previous pair before opening another, keeping peak native memory bounded
 *    alongside the resident recogniser.
 *  - A neural failure is never fatal: it degrades to eSpeak and reports once.
 */
class TtsRouter(
    private val espeak: SpeechSynthesizerEngine,
    private val lookup: suspend (LanguageCode) -> VoicePack?,
    private val onFallback: (LanguageCode, String) -> Unit = { _, _ -> }
) : SpeechSynthesizerEngine {

    private val lock = Mutex()
    private var neural: FastPitchHifiGanEngine? = null
    private var residentLanguage: LanguageCode? = null

    /** Languages whose neural voice failed; not retried until selection changes. */
    private val degraded = mutableSetOf<LanguageCode>()

    @Volatile private var active: SpeechSynthesizerEngine? = null

    override fun supports(language: LanguageCode) = espeak.supports(language)

    override suspend fun prepare() {
        // Only the bundled voice is warmed. A neural pack loads on first use so
        // startup never pays for a model the user may not need.
        espeak.prepare()
    }

    override suspend fun synthesize(request: TtsRequest): PcmAudio {
        val engine = select(request)
        active = engine
        return try {
            engine.synthesize(request)
        } catch (error: Throwable) {
            if (engine === espeak) throw error
            // Keep the message audible: fall back rather than losing it.
            report(request.language, error)
            releaseNeural()
            active = espeak
            espeak.synthesize(request)
        } finally {
            active = null
        }
    }

    private suspend fun select(request: TtsRequest): SpeechSynthesizerEngine {
        val language = request.language
        if (language in degraded) return espeak
        if (request.emergency) {
            // Use a resident neural voice if it already matches, never load one.
            val resident = neural
            return if (resident != null && residentLanguage == language) resident else espeak
        }
        lock.withLock {
            neural?.let { if (residentLanguage == language) return it }
            val pack = try {
                lookup(language)
            } catch (error: Throwable) {
                report(language, error)
                null
            } ?: return espeak
            releaseNeuralLocked()
            val candidate = FastPitchHifiGanEngine(pack)
            return try {
                candidate.prepare()
                neural = candidate
                residentLanguage = language
                candidate
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                report(language, error)
                espeak
            }
        }
    }

    private fun report(language: LanguageCode, error: Throwable) {
        if (degraded.add(language)) {
            Log.w("ItantraTts", "Neural voice unavailable for ${language.code}", error)
            onFallback(
                language,
                error.message ?: "The neural voice could not run; the built-in voice was used."
            )
        }
    }

    /** Call after installing, removing or switching a voice so the choice applies. */
    suspend fun invalidate(language: LanguageCode? = null) = lock.withLock {
        if (language == null) degraded.clear() else degraded.remove(language)
        if (language == null || residentLanguage == language) releaseNeuralLocked()
    }

    private suspend fun releaseNeural() = lock.withLock { releaseNeuralLocked() }

    private fun releaseNeuralLocked() {
        neural?.let { runCatching { it.close() } }
        neural = null
        residentLanguage = null
    }

    /** True when the language will speak with a neural voice right now. */
    suspend fun usesNeuralVoice(language: LanguageCode): Boolean =
        language !in degraded && runCatching { lookup(language) != null }.getOrDefault(false)

    override fun stop() {
        // Stop whichever engine owns the current utterance, plus eSpeak, because
        // a cancelled neural synthesis may already have fallen through to it.
        active?.let { runCatching { it.stop() } }
        runCatching { espeak.stop() }
        neural?.let { runCatching { it.stop() } }
    }

    override fun close() {
        neural?.let { runCatching { it.close() } }
        neural = null
        residentLanguage = null
        espeak.close()
    }
}
