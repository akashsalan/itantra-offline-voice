package org.itantra.app

import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.asr.speechRecognizerFor
import org.itantra.app.core.*
import org.itantra.app.models.ModelPacks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Fixed human recording, not microphone WER. Candidate imports use disposable private storage only. */
@RunWith(AndroidJUnit4::class)
class EnglishParakeetSmokeTest {
    @Test fun englishImportAndDecode() = checkCandidate(PackCatalog.englishParakeet, "itantra-en.itpack")

    private fun checkCandidate(profile: PackProfile, archiveName: String) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val productionPointer = File(context.noBackupFilesDir, "models/active-en")
        val before = productionPointer.takeIf { it.exists() }?.readText()
        val root = File(context.cacheDir, "english-comparison-${UUID.randomUUID()}")
        val reportDirectory = File(checkNotNull(context.getExternalFilesDir(null)), "english-parakeet").apply { mkdirs() }
        val fixture = File(checkNotNull(context.getExternalFilesDir(null)), "english-trial/0.wav").readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        assertEquals("6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f", hash)
        val waveform = pcm16Mono16k(fixture)
        var engine: SpeechRecognizerEngine? = null
        val report = JSONObject().put("kind", "android_fixed_human_fixture_not_microphone_WER")
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("pack_id", profile.id)
            .put("fixture_sha256", hash).put("threads", 2).put("provider", "cpu")
        try {
            val models = ModelPacks(context, root)
            val progress = mutableListOf<Int>()
            models.importStream({ it.percent?.let(progress::add) }) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$archiveName"))
            }
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            val pack = checkNotNull(models.installed(LanguageCode.EN))
            assertEquals(profile.id, pack.packId)
            val recognizer = speechRecognizerFor(pack).also { engine = it }
            val loaded = recognizer.load(pack)
            report.put("model_load_ms", loaded.elapsedMs).put("model_bytes_including_license", pack.bytes)
            val rows = JSONArray()
            var previous: String? = null
            repeat(2) { iteration ->
                recognizer.reset()
                waveform.toList().chunked(1600).forEach { recognizer.acceptPcm16(it.toShortArray()) }
                val result = recognizer.finish()
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                rows.put(JSONObject().put("iteration", iteration).put("text", result.text)
                    .put("audio_seconds", result.audioSeconds).put("inference_ms", result.inferenceMs)
                    .put("rtf", result.inferenceMs / (1000 * result.audioSeconds))
                    .put("pss_after_decode_kb_not_peak", memory.totalPss))
                report.put("rows", rows)
                assertTrue("Empty speech result", result.text.isNotBlank())
                assertTrue("Known phrase missing from fixture", result.text.lowercase().contains("yellow lamps"))
                if (previous != null) assertEquals("Reset changed deterministic result", previous, result.text)
                previous = result.text
                assertEquals(waveform.size / 16000.0, result.audioSeconds, .0001)
            }
            recognizer.reset(); recognizer.acceptPcm16(ShortArray(48000))
            val silence = recognizer.finish().text
            report.put("digital_silence_text", silence).put("digital_silence_blank", silence.isBlank())
            // The app rejects digital silence before decoding. Record decoder behavior separately.
            recognizer.reset()
            assertTrue(runCatching { recognizer.acceptPcm16(ShortArray(16000 * 16)) }.isFailure)
            report.put("fifteen_second_buffer_limit_passed", true).put("import_progress_passed", true)
                .put("speech_decode_reset_passed", true)
        } catch (error: Throwable) {
            report.put("error", error.toString()); throw error
        } finally {
            engine?.close()
            report.put("production_pointer_unchanged", before == productionPointer.takeIf { it.exists() }?.readText())
            File(reportDirectory, "${profile.id}.json").writeText(report.toString(2))
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
            assertEquals("Test must not activate a candidate in the user's app", before, productionPointer.takeIf { it.exists() }?.readText())
        }
    }
}
