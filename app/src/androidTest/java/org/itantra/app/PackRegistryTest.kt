package org.itantra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.core.*
import org.itantra.app.models.ModelPacks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tiny pinned fixtures and a private test root: never import over the user's installed models. */
@RunWith(AndroidJUnit4::class)
class PackRegistryTest {
    private val profiles = PackCatalog.profiles.filter { it.language == LanguageCode.EN }
    private fun payload(profile: PackProfile) = "pinned test vocabulary ${profile.id}".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun catalog() = JSONObject().put("artifacts", JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("path", profile.sourcePrefix + "tokens.txt")
                .put("bytes", payload(profile).size).put("sha256", sha(payload(profile))))
        }
    })
    private fun pack(profile: PackProfile, engine: String = profile.engine.wireId, extra: String? = null,
        corrupt: Boolean = false, missing: Boolean = false): ByteArray {
        val bytes = payload(profile)
        val manifest = JSONObject().put("schemaVersion", 1).put("sampleRate", 16000).put("packId", profile.id)
            .put("language", "en").put("engine", engine).put("files", JSONArray().put(JSONObject()
                .put("path", "tokens.txt").put("bytes", bytes.size).put("sha256", sha(bytes))))
        return ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                if (!missing) {
                    zip.putNextEntry(ZipEntry("tokens.txt")); zip.write(if (corrupt) ByteArray(bytes.size) else bytes); zip.closeEntry()
                }
                if (extra != null) { zip.putNextEntry(ZipEntry(extra)); zip.write(byteArrayOf(1)); zip.closeEntry() }
            }
        }.toByteArray()
    }
    private fun fixture(block: suspend (ModelPacks, File) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "pack-registry-${UUID.randomUUID()}")
        try { block(ModelPacks(context, root, catalog()), root) }
        finally { check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)); root.deleteRecursively() }
    }
    @Test fun englishImportProgressAndStoreRecreation() = fixture { models, root ->
        val profile = PackCatalog.englishParakeet
        repeat(2) {
            val percentages = mutableListOf<Int>()
            models.importStream({ it.percent?.let(percentages::add) }) { pack(profile).inputStream() }
            assertEquals(100, percentages.last())
            assertTrue(percentages.zipWithNext().all { (a, b) -> b >= a })
        }
        val reopened = ModelPacks(InstrumentationRegistry.getInstrumentation().targetContext, root, catalog())
        val installed = checkNotNull(reopened.installedProfile(profile))
        reopened.activateInstalled(installed)
        assertEquals(profile.id, reopened.installed(LanguageCode.EN)?.packId)
        assertEquals(profile.engine.wireId, installed.engine)
    }
    @Test fun previousActivePointerMigratesWithoutReimport() = fixture { models, root ->
        val profile = PackCatalog.english
        val version = "b".repeat(32)
        File(root, "${profile.id}/$version").mkdirs()
        File(root, "${profile.id}/$version/tokens.txt").writeBytes(payload(profile))
        File(root, "active-en").writeText(ActivePack(profile, version).pointerText())
        models.importStream { pack(PackCatalog.englishParakeet).inputStream() }
        val original = checkNotNull(models.installedProfile(profile))
        assertEquals(version, original.directory.name)
        assertTrue(runCatching { models.activateInstalled(original) }.isFailure)
        assertEquals(PackCatalog.englishParakeet.id, models.installed(LanguageCode.EN)?.packId)
    }
    @Test fun legacyUuidPointerIsRetainedWhenImportingCandidate() = fixture { models, root ->
        val profile = PackCatalog.englishLegacy
        val version = "c".repeat(32)
        File(root, "${profile.id}/$version").mkdirs()
        File(root, "${profile.id}/$version/tokens.txt").writeBytes(payload(profile))
        File(root, "active-en").writeText(version)
        assertNull(models.installed(LanguageCode.EN))
        models.importStream { pack(PackCatalog.englishParakeet).inputStream() }
        assertTrue(runCatching { models.activateInstalled(checkNotNull(models.installedProfile(profile))) }.isFailure)
        assertEquals(PackCatalog.englishParakeet.id, models.installed(LanguageCode.EN)?.packId)
    }
    @Test fun invalidArchivesDoNotReplaceTheActivePack() = fixture { models, root ->
        models.importStream { pack(PackCatalog.englishParakeet).inputStream() }
        val before = File(root, "active-en").readText()
        val candidate = PackCatalog.englishParakeet
        val invalid = listOf(pack(candidate, engine = AsrEngine.ZIPFORMER.wireId),
            pack(candidate, corrupt = true), pack(candidate, missing = true), pack(candidate, extra = "../escape"),
            pack(candidate, extra = "unexpected.onnx")) + PackCatalog.retiredEnglish.map { pack(it) }
        for (archive in invalid) {
            assertTrue(runCatching { models.importStream { archive.inputStream() } }.isFailure)
            assertEquals(before, File(root, "active-en").readText())
            assertEquals(PackCatalog.englishParakeet.id, models.installed(LanguageCode.EN)?.packId)
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".import-") })
        }
    }
    @Test fun corruptedInstalledCandidateCannotBecomeActive() = fixture { models, root ->
        models.importStream { pack(PackCatalog.englishParakeet).inputStream() }
        val broken = checkNotNull(models.installedProfile(PackCatalog.englishParakeet))
        val file = File(broken.directory, "tokens.txt")
        file.setWritable(true); file.writeBytes(ByteArray(file.length().toInt()))
        val before = File(root, "active-en").readText()
        assertTrue(runCatching { models.activateInstalled(broken) }.isFailure)
        assertEquals(before, File(root, "active-en").readText())
        assertTrue(models.statuses().single { it.profileId == PackCatalog.englishParakeet.id }.error != null)
    }
    @Test fun rejectedSmallStreamingLeavesPreviousPackAndPointerIntact() = fixture { models, root ->
        models.importStream { pack(PackCatalog.englishParakeet).inputStream() }
        val before = File(root, "active-en").readText()
        val checking = ModelPacks(InstrumentationRegistry.getInstrumentation().targetContext, root, catalog()) {
            throw IllegalStateException("Simulated native load failure")
        }
        assertTrue(runCatching { checking.importStream { pack(PackCatalog.englishMoonshineSmall).inputStream() } }.isFailure)
        assertEquals(before, File(root, "active-en").readText())
        assertNotNull(models.installed(LanguageCode.EN))
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".import-") })
        models.importStream { pack(PackCatalog.englishMoonshineSmall).inputStream() }
        assertEquals(PackCatalog.englishMoonshineSmall.id, models.installed(LanguageCode.EN)?.packId)
        val previous = checkNotNull(models.installedProfile(PackCatalog.englishParakeet))
        models.activateInstalled(previous)
        assertEquals(before, File(root, "active-en").readText())
    }
    @Test fun englishVariantsPersistIndependentlyAndDeleteOnlyChosenProfile() = fixture { models, root ->
        val small = PackCatalog.englishMoonshineSmall
        val tiny = PackCatalog.englishMoonshineTiny
        models.importStream { pack(small).inputStream() }
        val before = File(root, "active-en").readText()
        models.importStream(makeActive = false) { pack(tiny).inputStream() }
        assertEquals(before, File(root, "active-en").readText())
        assertEquals(2, models.statuses().count { it.language == LanguageCode.EN && it.installed })
        models.activateInstalled(checkNotNull(models.installedProfile(tiny)))
        val reopened = ModelPacks(InstrumentationRegistry.getInstrumentation().targetContext, root, catalog())
        assertEquals(tiny.id, reopened.installed(LanguageCode.EN)?.packId)
        assertFalse(PackCatalog.activateBundled(small, reopened.installed(LanguageCode.EN)?.packId))
        val selectedTiny = File(root, "active-en").readText()
        reopened.remove(LanguageCode.EN, small.id)
        assertEquals(selectedTiny, File(root, "active-en").readText())
        assertNotNull(reopened.installedProfile(tiny)); assertNull(reopened.installedProfile(small))
        assertFalse(reopened.shouldInstallBundled(small)); assertTrue(reopened.shouldInstallBundled(tiny))
        reopened.activateInstalled(checkNotNull(reopened.installedProfile(tiny)))
        assertFalse(reopened.shouldInstallBundled(small)) // Choosing Tiny must not undo Small's opt-out.
        reopened.importStream(makeActive = false) { pack(small).inputStream() }
        reopened.activateInstalled(checkNotNull(reopened.installedProfile(small)))
        assertEquals(small.id, reopened.installed(LanguageCode.EN)?.packId)
    }
}
