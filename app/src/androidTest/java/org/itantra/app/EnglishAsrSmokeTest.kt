package org.itantra.app

import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.asr.SherpaOnlineTransducerEngine
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.PackCatalog
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

/** Precondition: import en-v2.itpack and push the pinned 0.wav to app external files. */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Zipformer retired in code 12; historical results are preserved under docs/results")
class EnglishAsrSmokeTest {
    @Test fun importedEnglishRecognizesPinnedHumanFixtureAfterReset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "english-trial")
        val fixture = File(directory, "0.wav").readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        assertEquals("Wrong test recording", "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f", sha)
        val samples = pcm16Mono16k(fixture)
        val pack = checkNotNull(ModelPacks(context).installedProfile(PackCatalog.english)) { "Import updated English pack first" }
        assertEquals("Legacy English still active", PackCatalog.english.id, pack.packId)
        val engine = SherpaOnlineTransducerEngine()
        val rows = JSONArray()
        try {
            val loaded = engine.load(pack)
            repeat(2) { iteration ->
                engine.reset()
                var offset = 0
                while (offset < samples.size) {
                    val end = minOf(offset + 1600, samples.size)
                    engine.acceptPcm16(samples.copyOfRange(offset, end))
                    offset = end
                }
                val result = engine.finish()
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                rows.put(JSONObject().put("iteration", iteration).put("text", result.text)
                    .put("audio_seconds", result.audioSeconds).put("inference_ms", result.inferenceMs)
                    .put("rtf", result.inferenceMs / (1000 * result.audioSeconds))
                    .put("pss_after_decode_kb", memory.totalPss))
                assertEquals("AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP HERE AND THERE THE SQUALID QUARTER OF THE BROTHELS", result.text)
                assertEquals(samples.size / 16000.0, result.audioSeconds, 0.0001)
            }
            engine.reset()
            engine.acceptPcm16(ShortArray(48000))
            assertEquals("Digital silence should not produce a transcript", "", engine.finish().text)
            File(directory, "result.json").writeText(JSONObject()
                .put("kind", "android_fixed_fixture_not_microphone_accuracy")
                .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("pack_id", pack.packId).put("model_bytes", pack.bytes)
                .put("fixture_sha256", sha).put("model_load_ms", loaded.elapsedMs)
                .put("digital_silence_blank", true).put("rows", rows).toString(2))
        } finally { engine.close() }
    }

}

    internal fun pcm16Mono16k(bytes: ByteArray): ShortArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        input.position(12)
        var formatOkay = false
        while (input.remaining() >= 8) {
            val name = ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
            val size = input.int
            require(size >= 0 && size <= input.remaining())
            val start = input.position()
            if (name == "fmt ") {
                require(size >= 16)
                require(input.short.toInt() == 1 && input.short.toInt() == 1)
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
        error("No PCM data in pinned fixture")
    }
