package org.itantra.app.models

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.itantra.app.core.ActiveVoice
import org.itantra.app.core.ImportProgressTracker
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.PackImportProgress
import org.itantra.app.core.TtsEngineKind
import org.itantra.app.core.VoiceCatalog
import org.itantra.app.core.VoicePack
import org.itantra.app.core.VoiceProfile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class VoiceStatus(
    val language: LanguageCode,
    val packId: String,
    val label: String,
    val bytes: Long,
    val installed: Boolean,
    val active: Boolean,
    /** False when no pack for this language is pinned in the catalogue yet. */
    val available: Boolean = true,
    val error: String? = null
)

/**
 * Installs neural voice packs. Intentionally a separate store from [ModelPacks]:
 * voice pointers live in their own namespace so nothing here can disturb an
 * installed speech-recognition pack, and a failure domain stays isolated.
 *
 * Validation matches the recogniser importer: manifest first, exact flat
 * allowlist (no zip-slip, no executable entries), declared sizes and SHA-256
 * checked against the pinned catalogue, two verification passes, and activation
 * only after the staged copy is complete.
 */
class VoicePacks internal constructor(
    private val context: Context,
    private val root: File = File(context.noBackupFilesDir, "voices"),
    private val catalogOverride: JSONObject? = null
) {
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create voice storage" } }

    private val lock by lazy {
        (catalogOverride ?: JSONObject(
            context.assets.open("models.lock.json").bufferedReader().use { it.readText() }
        )).getJSONArray("artifacts")
    }

    fun expected(profile: VoiceProfile): List<PackFile> =
        (0 until lock.length()).map { lock.getJSONObject(it) }
            .filter { it.getString("path").startsWith(profile.sourcePrefix) }
            .map {
                PackFile(
                    it.getString("path").substringAfterLast('/'),
                    it.getLong("bytes"),
                    it.getString("sha256")
                )
            }

    private fun pointer(language: LanguageCode) = AtomicFile(File(root, "active-${language.code}"))
    private fun profilePointer(profile: VoiceProfile) = AtomicFile(File(root, "installed-${profile.id}"))
    private fun optOut(profile: VoiceProfile) = File(root, "skip-bundled-${profile.id}")

    fun shouldInstallBundled(profile: VoiceProfile) = !optOut(profile).exists()

    private fun read(file: AtomicFile, language: LanguageCode): ActiveVoice? {
        val value = try {
            file.openRead().bufferedReader().use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            return null
        }
        return VoiceCatalog.parsePointer(language, value)
    }

    private fun write(file: AtomicFile, selected: ActiveVoice) {
        val stream = file.startWrite()
        try {
            stream.write(selected.pointerText().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun verified(selected: ActiveVoice): VoicePack {
        val profile = selected.profile
        val directory = File(File(root, profile.id), selected.version)
        val files = expected(profile)
        require(files.isNotEmpty()) { "Voice pack has no pinned files" }
        files.forEach { verify(File(directory, it.name), it) }
        return VoicePack(
            profile.language, profile.engine.wireId, directory,
            files.sumOf { it.bytes }, profile.id, profile.sampleRate, profile.topology
        )
    }

    private fun registered(profile: VoiceProfile): ActiveVoice? =
        read(profilePointer(profile), profile.language)?.also {
            require(it.profile == profile) { "Installed voice identity mismatch" }
        }

    private fun activate(selected: ActiveVoice) {
        write(profilePointer(selected.profile), selected)
        write(pointer(selected.profile.language), selected)
        optOut(selected.profile).delete()
    }

    /** The voice selected for a language, or null when the language uses eSpeak. */
    suspend fun installed(language: LanguageCode): VoicePack? = withContext(Dispatchers.IO) {
        runCatching { read(pointer(language), language)?.let(::verified) }.getOrNull()
    }

    suspend fun installedProfile(profile: VoiceProfile): VoicePack? = withContext(Dispatchers.IO) {
        registered(profile)?.let(::verified)
    }

    suspend fun statuses(): List<VoiceStatus> = withContext(Dispatchers.IO) {
        VoiceCatalog.profiles.map { profile ->
            val active = runCatching { read(pointer(profile.language), profile.language)?.profile }.getOrNull()
            val pinned = expected(profile)
            val size = pinned.sumOf { it.bytes }
            try {
                val pack = if (pinned.isEmpty()) null else installedProfile(profile)
                VoiceStatus(
                    profile.language, profile.id, profile.label,
                    pack?.bytes ?: size, pack != null, active == profile, pinned.isNotEmpty()
                )
            } catch (error: Exception) {
                VoiceStatus(
                    profile.language, profile.id, profile.label, size, false, false,
                    pinned.isNotEmpty(), error.message
                )
            }
        }
    }

    /** Turns a language back to the bundled eSpeak voice without deleting files. */
    suspend fun useEspeak(language: LanguageCode) = withContext(Dispatchers.IO) {
        pointer(language).delete()
    }

    suspend fun activateInstalled(pack: VoicePack) = withContext(Dispatchers.IO) {
        val profile = VoiceCatalog.resolve(pack.language, pack.packId)
        val selected = requireNotNull(registered(profile)) { "Import this voice first" }
        require(verified(selected) == pack) { "Installed voice changed during selection" }
        activate(selected)
    }

    suspend fun import(uri: Uri, onProgress: (PackImportProgress) -> Unit = {}): LanguageCode =
        importStream(onProgress) {
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open voice pack" }
        }

    suspend fun importAsset(
        name: String,
        onProgress: (PackImportProgress) -> Unit = {},
        makeActive: Boolean = true
    ): LanguageCode = importStream(onProgress, makeActive) { context.assets.open("starter-voices/$name") }

    /** Caller must release the engine first. Removes only the registered version. */
    suspend fun remove(language: LanguageCode, expectedPackId: String) = withContext(Dispatchers.IO) {
        val profile = VoiceCatalog.resolve(language, expectedPackId)
        val selected = requireNotNull(registered(profile)) { "No installed voice to remove." }
        val wasActive = read(pointer(language), language) == selected
        val target = File(File(root, profile.id), selected.version).canonicalFile
        val parent = File(root, profile.id).canonicalFile
        require(parent.parentFile == root.canonicalFile && target.parentFile == parent)
        // Remember the deletion so the preloaded flavour will not reinstall it.
        val marker = AtomicFile(optOut(profile))
        val output = marker.startWrite()
        try { output.write(1); marker.finishWrite(output) } catch (error: Exception) {
            marker.failWrite(output); throw error
        }
        require(!target.exists() || target.deleteRecursively()) {
            "Could not remove all voice files. Retry deletion."
        }
        if (wasActive) pointer(language).delete()
        profilePointer(profile).delete()
    }

    internal suspend fun importStream(
        onProgress: (PackImportProgress) -> Unit = {},
        makeActive: Boolean = true,
        open: () -> InputStream
    ): LanguageCode = withContext(Dispatchers.IO) {
        onProgress(PackImportProgress())
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            ZipInputStream(open().buffered()).use { zip ->
                val first = requireNotNull(zip.nextEntry) { "Empty voice pack" }
                require(first.name == "manifest.json" && !first.isDirectory) {
                    "manifest.json must be first"
                }
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 64 * 1024) { "Manifest too large" }
                    bytes.write(buffer, 0, count)
                }
                val manifest = JSONObject(bytes.toString("UTF-8"))
                require(manifest.getInt("schemaVersion") == 1) { "Unsupported voice pack version" }
                require(manifest.getString("kind") == "tts") {
                    "This is a speech-recognition pack. Import it from the Models tab."
                }
                val language = LanguageCode.fromCode(manifest.getString("language"))
                val profile = VoiceCatalog.resolve(language, manifest.getString("packId"))
                require(manifest.getString("engine") == profile.engine.wireId) {
                    "Voice pack engine does not match its pinned profile"
                }
                require(manifest.getInt("sampleRate") == profile.sampleRate) {
                    "Voice pack sample rate does not match its pinned profile"
                }
                val expected = expected(profile)
                require(expected.isNotEmpty()) { "Voice pack is not in the pinned catalogue" }
                require(root.usableSpace >= expected.sumOf { it.bytes } + 16L * 1024 * 1024) {
                    "Not enough storage to install this voice safely"
                }
                val declarations = manifest.getJSONArray("files")
                require(declarations.length() == expected.size)
                val declared = (0 until declarations.length()).map { declarations.getJSONObject(it) }
                require(declared.map { it.getString("path") }.toSet().size == expected.size)
                expected.forEach { wanted ->
                    val entry = declared.single { it.getString("path") == wanted.name }
                    require(
                        entry.getString("sha256") == wanted.sha256 && entry.getLong("bytes") == wanted.bytes
                    ) { "Voice pack differs from pinned catalog" }
                }

                val progress = ImportProgressTracker(language, expected.sumOf { it.bytes }, onProgress)
                val seen = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val wanted = expected.singleOrNull { it.name == entry.name }
                    require(wanted != null && !entry.isDirectory && seen.add(entry.name)) {
                        "Unexpected or duplicate voice entry: ${entry.name}"
                    }
                    val output = File(staging, wanted.name)
                    output.outputStream().use { sink ->
                        val chunk = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = zip.read(chunk)
                            if (count < 0) break
                            copied += count
                            require(copied <= wanted.bytes) { "Voice entry exceeds declared size" }
                            sink.write(chunk, 0, count)
                            progress.copied(count, wanted.name)
                        }
                        require(copied == wanted.bytes) { "Incomplete voice file" }
                        sink.fd.sync()
                    }
                    verify(output, wanted) { progress.verified(it, wanted.name) }
                }
                require(seen.size == expected.size) { "Missing voice files" }

                val version = UUID.randomUUID().toString().replace("-", "")
                val parent = File(root, profile.id).apply { mkdirs() }
                val destination = File(parent, version)
                check(staging.renameTo(destination)) { "Cannot finalize voice pack" }
                expected.forEach { wanted ->
                    verify(File(destination, wanted.name), wanted) {
                        progress.verified(it, wanted.name, finalCheck = true)
                    }
                    File(destination, wanted.name).setReadOnly()
                }
                val selected = ActiveVoice(profile, version)
                if (makeActive) activate(selected) else {
                    write(profilePointer(profile), selected)
                    optOut(profile).delete()
                }
                progress.committed()
                language
            }
        } finally { staging.deleteRecursively() }
    }

    private fun verify(file: File, expected: PackFile, onBytes: (Int) -> Unit = {}) {
        require(file.isFile && file.length() == expected.bytes) {
            "Missing/wrong-size ${expected.name}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                onBytes(count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected.sha256) { "SHA-256 mismatch: ${expected.name}" }
    }

    companion object {
        /** Voice packs use the TTS engine registry, never the recogniser one. */
        val engine = TtsEngineKind.FASTPITCH_HIFIGAN
    }
}
