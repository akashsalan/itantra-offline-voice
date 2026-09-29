package org.itantra.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The attention stage that runs before an incoming emergency is spoken.
 *
 * Someone with the phone in a pocket and the screen off will not notice speech
 * alone, so an alert first vibrates and sounds on the alarm stream, then stops so
 * the spoken words are not competing with a tone.
 *
 * Deliberately bounded and cancellable: it never loops forever, and a user
 * acknowledging or stopping the alert cancels the coroutine that called it.
 */
class EmergencyAlarm(private val context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    /**
     * Vibrates and sounds for [durationMs], then falls silent.
     *
     * Failures are swallowed on purpose: a missing vibrator, a denied tone
     * generator or an OEM restriction must never stop the message itself from
     * being spoken, which is the part that carries the information.
     */
    suspend fun announce(durationMs: Long = DEFAULT_MS) = withContext(Dispatchers.IO) {
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        }.getOrNull()
        runCatching { startVibration(durationMs) }
        try {
            // Repeated short bursts read as urgent; one long tone reads as an error.
            val cycle = 900L
            var elapsed = 0L
            while (elapsed < durationMs) {
                runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 450) }
                delay(minOf(cycle, durationMs - elapsed))
                elapsed += cycle
            }
        } finally {
            runCatching { tone?.stopTone() }
            runCatching { tone?.release() }
            runCatching { vibrator?.cancel() }
        }
    }

    private fun startVibration(durationMs: Long) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(waveform(durationMs), -1)
        // Android 12 introduced vibration usages, which is what marks this as an
        // alarm rather than a notification buzz. Older releases carry the same
        // intent through AudioAttributes instead.
        if (Build.VERSION.SDK_INT >= 31 && vibrateAsAlarm(effect)) return
        @Suppress("DEPRECATION")
        device.vibrate(
            effect,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
    }

    /** Returns false when the platform has no vibrator manager, so the caller can fall back. */
    @RequiresApi(31)
    private fun vibrateAsAlarm(effect: VibrationEffect): Boolean {
        val manager = context.getSystemService(VibratorManager::class.java) ?: return false
        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_ALARM).build()
        manager.vibrate(CombinedVibration.createParallel(effect), attributes)
        return true
    }

    /** buzz, gap, buzz, gap … for roughly [durationMs]. */
    private fun waveform(durationMs: Long): LongArray {
        val unit = 450L
        return ArrayList<Long>().apply {
            add(0L)
            var elapsed = 0L
            while (elapsed < durationMs) {
                add(unit); add(unit / 2)
                elapsed += unit + unit / 2
            }
        }.toLongArray()
    }

    fun stop() {
        runCatching { vibrator?.cancel() }
    }

    companion object {
        /** Long enough to notice from a pocket, short enough not to delay the words. */
        const val DEFAULT_MS = 3_500L
    }
}
