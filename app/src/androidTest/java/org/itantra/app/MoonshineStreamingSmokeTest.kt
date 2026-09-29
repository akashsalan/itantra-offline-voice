package org.itantra.app

import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.asr.speechRecognizerFor
import org.itantra.app.audio.NeuralVad
import org.itantra.app.core.*
import org.itantra.app.models.ModelPacks
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One fixed human fixture and two load orders, not an accuracy or device benchmark. */
@RunWith(AndroidJUnit4::class)
class MoonshineStreamingSmokeTest {
    @Test fun isolatedRuntimeAndStreamingDecode() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sherpaFirst = InstrumentationRegistry.getArguments().getString("loadOrder") == "sherpa-first"
        val tiny = InstrumentationRegistry.getArguments().getString("modelVariant") == "tiny"
        val profile = if (tiny) PackCatalog.englishMoonshineTiny else PackCatalog.englishMoonshineSmall
        val archiveName = if (tiny) "itantra-moonshine-tiny.itpack" else "itantra-moonshine-small.itpack"
        val productionRoot = File(context.noBackupFilesDir, "models")
        val before = productionRoot.listFiles().orEmpty().filter { it.name.startsWith("active-") }.associate { it.name to it.readText() }
        val root = File(context.cacheDir, "small-streaming-smoke-${UUID.randomUUID()}")
        val report = JSONObject().put("kind", "fixed_fixture_smoke_not_accuracy_or_benchmark")
            .put("packId", profile.id)
            .put("loadOrder", if (sherpaFirst) "sherpa-first" else "moonshine-first")
        try {
            if (sherpaFirst) NeuralVad(context.assets).use { assertFalse(it.speech(ShortArray(512), 0)) }
            val packs = ModelPacks(context, root)
            packs.importStream {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$archiveName"))
            }
            val pack = checkNotNull(packs.installed(LanguageCode.EN))
            assertEquals(profile.id, pack.packId)
            val fixture = File(checkNotNull(context.getExternalFilesDir(null)), "english-trial/0.wav").readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
            assertEquals("6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f", hash)
            val pcm = pcm16Mono16k(fixture)
            speechRecognizerFor(pack).use { engine ->
                report.put("loadMs", engine.load(pack).elapsedMs)
                assertTrue(engine.acceptsLivePcm)
                NeuralVad(context.assets).use { vad ->
                    assertFalse(vad.speech(ShortArray(512), 0))
                    var speech = false
                    for (offset in 0..pcm.size - 512 step 512) speech = vad.speech(pcm, offset) || speech
                    assertTrue("Existing neural VAD must still detect the fixture", speech)
                }
                engine.reset()
                for (offset in pcm.indices step 320) engine.acceptPcm16(pcm.copyOfRange(offset, minOf(offset + 320, pcm.size)))
                val result = engine.finish()
                assertTrue("Known phrase missing from speech fixture", result.text.lowercase().contains("yellow lamps"))
                assertEquals(pcm.size / 16000.0, result.audioSeconds, .0001)
                assertSame("Finalization must be cached", result, engine.finish())
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                report.put("asrInferenceMs", result.inferenceMs).put("audioSeconds", result.audioSeconds)
                    .put("pssAfterDecodeKbNotPeak", memory.totalPss).put("fixturePhrasePassed", true)
                engine.reset(); engine.acceptPcm16(ShortArray(1600)); engine.reset()
            }
            // The same process must still load the existing Hindi sherpa recognizer.
            val hindi = ModelPacks(context).installed(LanguageCode.HI)
            assertNotNull("Installed Hindi preserved", hindi)
            speechRecognizerFor(hindi!!).use { it.load(hindi); it.reset(); it.acceptPcm16(ShortArray(1600)); it.finish() }
            NeuralVad(context.assets).use { assertFalse(it.speech(ShortArray(512), 0)) }
            report.put("hindiAndVadAfterMoonshinePassed", true)
        } finally {
            assertEquals(before, productionRoot.listFiles().orEmpty().filter { it.name.startsWith("active-") }.associate { it.name to it.readText() })
            report.put("productionPointersUnchanged", true)
            File(checkNotNull(context.getExternalFilesDir(null)), "moonshine-${if (tiny) "tiny" else "small"}-smoke-${if (sherpaFirst) "sherpa" else "moonshine"}.json").writeText(report.toString(2))
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
