package org.itantra.app

import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.asr.SherpaOfflineNemoCtcEngine
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.UnicodeText
import org.itantra.app.models.ModelPacks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Explicit fixture test: import the experimental pack and push odia-trial files first.
 * Synthetic fixtures establish runtime compatibility, never native-speaker accuracy.
 */
@RunWith(AndroidJUnit4::class)
class OdiaAsrSmokeTest {
    @Test fun importedOdiaMatchesDesktopFixturesAfterReset() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "odia-trial")
        val host = JSONObject(File(directory, "host.json").readText())
        assertTrue("Run desktop audit first", host.getBoolean("host_smoke_passed"))
        val pack = checkNotNull(ModelPacks(context).installed(LanguageCode.OR)) { "Import experimental Odia pack first" }
        assertEquals(host.getString("int8_sha256"), sha(File(pack.directory, "model.int8.onnx")))
        assertEquals(host.getString("tokens_sha256"), sha(File(pack.directory, "tokens.txt")))
        val engine = SherpaOfflineNemoCtcEngine()
        val rows = JSONArray()
        val running = AtomicBoolean(true)
        val peakPss = AtomicInteger(0)
        val sampler = thread(name = "odia-test-memory") {
            while (running.get()) {
                val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                peakPss.updateAndGet { maxOf(it, info.totalPss) }
                Thread.sleep(100)
            }
        }
        val result = JSONObject().put("kind", "android_synthetic_fixture_not_microphone_or_corpus_accuracy")
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("pack_id", pack.packId).put("model_bytes", pack.bytes)
            .put("model_sha256", host.getString("int8_sha256"))
            .put("native_speaker_sentences_scored", 0).put("release_validated", false)
            .put("rows", rows)
        try {
            val loaded = engine.load(pack)
            result.put("model_load_ms", loaded.elapsedMs)
            val fixtures = host.getJSONArray("rows")
            assertTrue(fixtures.length() >= 2)
            repeat(2) { iteration ->
                for (index in 0 until fixtures.length()) {
                    val reference = fixtures.getJSONObject(index)
                    val bytes = File(directory, "fixture-$index.wav").readBytes()
                    assertEquals("Fixture SHA mismatch", reference.getString("sha256"), sha(bytes))
                    val samples = pcm16Mono16k(bytes)
                    engine.reset()
                    samples.asList().chunked(1600).forEach { engine.acceptPcm16(it.toShortArray()) }
                    val decoded = engine.finish()
                    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    rows.put(JSONObject().put("fixture", index).put("iteration", iteration)
                        .put("fixture_sha256", reference.getString("sha256"))
                        .put("text", decoded.text).put("desktop_text", reference.getString("sherpa_transcript"))
                        .put("audio_seconds", decoded.audioSeconds).put("inference_ms", decoded.inferenceMs)
                        .put("rtf", decoded.inferenceMs / (1000 * decoded.audioSeconds))
                        .put("pss_after_decode_kb", memory.totalPss))
                    assertTrue("Empty Odia transcript", decoded.text.isNotBlank())
                    assertFalse("Invalid Unicode", decoded.text.contains('\uFFFD'))
                    assertEquals("Desktop / arm64 mismatch", UnicodeText.normalize(reference.getString("sherpa_transcript")), decoded.text)
                }
            }
            engine.reset()
            engine.acceptPcm16(ShortArray(48000))
            val silence = engine.finish().text
            result.put("silence_transcript", silence)
            assertEquals("Digital silence must be blank", "", silence)
            result.put("passed", true)
        } finally {
            engine.close()
            running.set(false)
            sampler.join(2000)
            result.put("sampled_peak_pss_kb", peakPss.get()).put("pss_sampling_interval_ms", 100)
            File(directory, "result.json").writeText(result.toString(2))
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pcm16Mono16k(bytes: ByteArray): ShortArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        input.position(12)
        var formatOkay = false
        while (input.remaining() >= 8) {
            val name = ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
            val size = input.int
            require(size >= 0 && size <= input.remaining())
            val start = input.position()
            if (name == "fmt ") {
                require(size >= 16 && input.short.toInt() == 1 && input.short.toInt() == 1)
                require(input.int == 16000)
                input.int
                require(input.short.toInt() == 2 && input.short.toInt() == 16)
                formatOkay = true
            } else if (name == "data") {
                require(formatOkay && size % 2 == 0)
                return ShortArray(size / 2) { input.short }
            }
            input.position(start + size + size % 2)
        }
        error("No PCM data in fixture")
    }
}
