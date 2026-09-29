package org.itantra.app.audio

import android.content.Context
import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.itantra.app.core.PcmAudio
import java.util.concurrent.atomic.AtomicBoolean

data class PlaybackTiming(val submittedNs: Long, val completedNs: Long, val completed: Boolean)
class AudioFocusUnavailable : IllegalStateException("Android audio focus is unavailable. Playback remains pending.")
class PcmPlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val stopped = AtomicBoolean(false)
    fun stop() { stopped.set(true) }
    suspend fun play(audio: PcmAudio, emergency: Boolean = false, authorizedVolumeBoost: Boolean = false,
        onSubmitted: (Long) -> Unit = {}, onVolumeRestricted: () -> Unit = {}, cancelled: () -> Boolean = { false }): PlaybackTiming = withContext(Dispatchers.IO) {
        require(audio.samples.isNotEmpty()) { "No TTS samples produced" }
        stopped.set(false)
        val attributes = AudioAttributes.Builder().setUsage(if (emergency) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focusLost = AtomicBoolean(false)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) { focusLost.set(true); stop() } }.build()
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.abandonAudioFocusRequest(focus)
            throw AudioFocusUnavailable()
        }
        var originalVolume: Int? = null
        var track: AudioTrack? = null
        try {
            if (emergency && authorizedVolumeBoost) {
                // A denied boost must not suppress speech at the user's existing volume.
                runCatching {
                    originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                }.onFailure { onVolumeRestricted() }
            }
            val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val output = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED)
            var started = SystemClock.elapsedRealtimeNanos()
            if (cancelled()) return@withContext PlaybackTiming(started, started, false)
            output.play()
            var written = 0
            while (written < audio.samples.size && !stopped.get() && !cancelled()) {
                val submitted = SystemClock.elapsedRealtimeNanos()
                val count = output.write(audio.samples, written, minOf(2048, audio.samples.size - written), AudioTrack.WRITE_BLOCKING)
                check(count > 0) { "AudioTrack write failed: $count" }
                if (written == 0) { started = submitted; onSubmitted(submitted) }
                written += count
            }
            val deadline = SystemClock.elapsedRealtime() + written * 1000L / audio.sampleRate + 2000
            while (!stopped.get() && !cancelled() && output.playbackHeadPosition.toLong() < written) {
                check(SystemClock.elapsedRealtime() < deadline) { "Playback did not drain" }
                delay(10)
            }
            if (focusLost.get() && !cancelled()) throw AudioFocusUnavailable()
            PlaybackTiming(started, SystemClock.elapsedRealtimeNanos(), !stopped.get() && !cancelled() && written == audio.samples.size)
        } finally {
            track?.let { runCatching { it.stop() }; it.release() }
            audioManager.abandonAudioFocusRequest(focus)
            originalVolume?.let { runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        }
    }
}
