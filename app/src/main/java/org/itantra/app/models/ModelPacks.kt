package org.itantra.app.models

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.itantra.app.core.AsrPack
import org.itantra.app.core.LanguageCode
import org.itantra.app.core.PackCatalog
import org.itantra.app.core.PackProfile
import org.itantra.app.core.ActivePack
import org.itantra.app.core.PackImportProgress
import org.itantra.app.core.ImportProgressTracker
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class PackFile(val name: String, val bytes: Long, val sha256: String)
data class PackStatus(val language: LanguageCode, val bytes: Long, val installed: Boolean, val packId: String? = null, val error: String? = null) {
    val experimental: Boolean get() = PackCatalog.preferred(language).experimental
    val profileId: String get() = packId ?: PackCatalog.preferred(language).id
}
class ModelPacks internal constructor(private val context: Context,
    private val root: File = File(context.noBackupFilesDir, "models"),
    private val catalogOverride: JSONObject? = null,
    private val beforeActivate: suspend (AsrPack) -> Unit = {}) {
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create model storage" } }
    private val lock by lazy {
        (catalogOverride ?: JSONObject(context.assets.open("models.lock.json").bufferedReader().use { it.readText() })).getJSONArray("artifacts")
    }
    fun expected(language: LanguageCode, packId: String = PackCatalog.preferred(language).id): List<PackFile> {
        val prefix = PackCatalog.resolve(language, packId).sourcePrefix
        return (0 until lock.length()).map { lock.getJSONObject(it) }.filter {
            it.getString("path").startsWith(prefix) || (language != LanguageCode.EN && it.getString("id") == "asr.indicconformer.shared_tokens")
        }.map {
            val path = it.getString("path")
            PackFile(if (path.endsWith("indic-tokens.txt")) "tokens.txt" else path.substringAfterLast('/'), it.getLong("bytes"), it.getString("sha256"))
        }
    }
    private fun pointer(language: LanguageCode) = AtomicFile(File(root, "active-${language.code}"))
    private fun bundledOptOut(language: LanguageCode) = File(root, "skip-bundled-${language.code}")
    fun shouldInstallBundled(language: LanguageCode) = !bundledOptOut(language).exists()
    private fun profileOptOut(profile: PackProfile) = File(root, "skip-bundled-${profile.id}")
    fun shouldInstallBundled(profile: PackProfile) = shouldInstallBundled(profile.language) && !profileOptOut(profile).exists()
    private fun profilePointer(profile: PackProfile) = AtomicFile(File(root, "installed-${profile.id}"))
    private fun read(file: AtomicFile, language: LanguageCode): ActivePack? {
        val value = try { file.openRead().bufferedReader().use { it.readText() } }
            catch (_: java.io.FileNotFoundException) { return null }
        return PackCatalog.parsePointer(language, value)
    }
    private fun write(file: AtomicFile, selected: ActivePack) {
        val stream = file.startWrite()
        try { stream.write(selected.pointerText().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun verified(selected: ActivePack): AsrPack {
        val profile = selected.profile
        val directory = File(File(root, profile.id), selected.version)
        val files = expected(profile.language, profile.id)
        require(files.isNotEmpty()) { "Pack has no pinned files" }
        files.forEach { verify(File(directory, it.name), it) }
        return AsrPack(profile.language, profile.engine.wireId, directory, files.sumOf { it.bytes }, profile.id)
    }
    private fun registered(profile: PackProfile): ActivePack? {
        val installed = read(profilePointer(profile), profile.language)
        if (installed != null) {
            require(installed.profile == profile) { "Installed pack identity mismatch" }
            return installed
        }
        // Migrate previous versions lazily without moving or copying the large files.
        return read(pointer(profile.language), profile.language)?.takeIf { it.profile == profile }
    }
    private fun activate(selected: ActivePack) {
        val old = runCatching { read(pointer(selected.profile.language), selected.profile.language) }.getOrNull()
        if (old != null) write(profilePointer(old.profile), old)
        write(profilePointer(selected.profile), selected)
        write(pointer(selected.profile.language), selected)
        bundledOptOut(selected.profile.language).delete()
        profileOptOut(selected.profile).delete()
    }
    suspend fun installed(language: LanguageCode): AsrPack? = withContext(Dispatchers.IO) {
        read(pointer(language), language)?.takeIf { PackCatalog.supported(it.profile) }?.let(::verified)
    }
    suspend fun installedProfile(profile: PackProfile): AsrPack? = withContext(Dispatchers.IO) {
        registered(profile)?.let(::verified)
    }
    suspend fun activateInstalled(pack: AsrPack) = withContext(Dispatchers.IO) {
        val profile = PackCatalog.resolve(pack.language, pack.packId)
        require(PackCatalog.supported(profile)) { "This English model has been retired. Import the new English pack." }
        val selected = requireNotNull(registered(profile)) { "Import this pack first" }
        require(verified(selected) == pack) { "Installed pack changed during selection" }
        activate(selected)
    }
    suspend fun statuses(): List<PackStatus> = withContext(Dispatchers.IO) {
        LanguageCode.entries.flatMap { language ->
            val active = runCatching { read(pointer(language), language)?.profile }.getOrNull()
            val choices = PackCatalog.choices(language) +
                if (active == PackCatalog.englishParakeet) listOfNotNull(active) else emptyList()
            choices.map { profile ->
                val size = expected(language, profile.id).sumOf { it.bytes }
                try { val pack = installedProfile(profile); PackStatus(language, pack?.bytes ?: size, pack != null, profile.id) }
                catch (error: Exception) { PackStatus(language, size, false, profile.id, error.message) }
            }
        }
    }
    suspend fun import(uri: Uri, onProgress: (PackImportProgress) -> Unit = {}): LanguageCode =
        importStream(onProgress) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open pack" } }
    suspend fun importAsset(name: String, onProgress: (PackImportProgress) -> Unit = {}, makeActive: Boolean = true): LanguageCode =
        importStream(onProgress, makeActive) { context.assets.open("starter-packs/$name") }
    /** Caller must unload/block use first. Only the currently registered immutable version is removed. */
    suspend fun remove(language: LanguageCode, expectedPackId: String) = withContext(Dispatchers.IO) {
        val profile = PackCatalog.resolve(language, expectedPackId)
        val selected = requireNotNull(registered(profile)) { "No installed model to remove." }
        val wasActive = read(pointer(language), language) == selected
        val target = File(File(root, selected.profile.id), selected.version).canonicalFile
        val parent = File(root, selected.profile.id).canonicalFile
        require(parent.parentFile == root.canonicalFile && target.parentFile == parent)
        // Remember an explicit deletion so the preloaded flavour will not silently reinstall it.
        val optOut = AtomicFile(profileOptOut(profile))
        val output = optOut.startWrite()
        try { output.write(1); optOut.finishWrite(output) } catch (error: Exception) { optOut.failWrite(output); throw error }
        require(!target.exists() || target.deleteRecursively()) { "Could not remove all model files. Retry deletion." }
        if (wasActive) pointer(language).delete()
        profilePointer(selected.profile).delete()
    }
    internal suspend fun importStream(onProgress: (PackImportProgress) -> Unit = {}, makeActive: Boolean = true,
        open: () -> InputStream): LanguageCode = withContext(Dispatchers.IO) {
        onProgress(PackImportProgress())
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val source = open()
            ZipInputStream(source.buffered()).use { zip ->
                val first = requireNotNull(zip.nextEntry) { "Empty pack" }
                require(first.name == "manifest.json" && !first.isDirectory) { "manifest.json must be first" }
                val manifestOutput = java.io.ByteArrayOutputStream()
                val manifestBuffer = ByteArray(4096)
                while (true) {
                    val count = zip.read(manifestBuffer)
                    if (count < 0) break
                    require(manifestOutput.size() + count <= 64 * 1024) { "Manifest too large" }
                    manifestOutput.write(manifestBuffer, 0, count)
                }
                val manifest = JSONObject(manifestOutput.toString("UTF-8"))
                require(manifest.getInt("schemaVersion") == 1 && manifest.getInt("sampleRate") == 16000)
                val language = LanguageCode.fromCode(manifest.getString("language"))
                val profile = PackCatalog.resolve(language, manifest.getString("packId"))
                require(PackCatalog.supported(profile)) { "This English pack has been replaced. Import English Moonshine Small Streaming." }
                val expected = expected(language, profile.id)
                require(expected.isNotEmpty())
                require(root.usableSpace >= expected.sumOf { it.bytes } + 16L * 1024 * 1024) { "Not enough storage to install this pack safely" }
                require(manifest.getString("engine") == profile.engine.wireId) { "Pack engine does not match its pinned profile" }
                val declarations = manifest.getJSONArray("files")
                require(declarations.length() == expected.size)
                val declared = (0 until declarations.length()).map { declarations.getJSONObject(it) }
                require(declared.map { it.getString("path") }.toSet().size == expected.size)
                expected.forEach { wanted ->
                    val entry = declared.single { it.getString("path") == wanted.name }
                    require(entry.getString("sha256") == wanted.sha256 && entry.getLong("bytes") == wanted.bytes) { "Pack differs from pinned catalog" }
                }
                val progress = ImportProgressTracker(language, expected.sumOf { it.bytes }, onProgress)
                val seen = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val wanted = expected.singleOrNull { it.name == entry.name }
                    require(wanted != null && !entry.isDirectory && seen.add(entry.name)) { "Unexpected or duplicate pack entry: ${entry.name}" }
                    // Exact flat allowlist prevents zip-slip and executable content.
                    val output = File(staging, wanted.name)
                    output.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= wanted.bytes) { "Pack entry exceeds declared size" }
                            sink.write(buffer, 0, count)
                            progress.copied(count, wanted.name)
                        }
                        require(copied == wanted.bytes) { "Incomplete pack file" }
                        sink.fd.sync()
                    }
                    verify(output, wanted) { progress.verified(it, wanted.name) }
                }
                require(seen.size == expected.size) { "Missing model files" }
                // Validation is before the atomic activation, while the previous model
                // and pointers remain intact. A rejected candidate is removed as staging.
                beforeActivate(AsrPack(language, profile.engine.wireId, staging, expected.sumOf { it.bytes }, profile.id))
                val version = UUID.randomUUID().toString().replace("-", "")
                val parent = File(root, profile.id).apply { mkdirs() }
                val destination = File(parent, version)
                check(staging.renameTo(destination)) { "Cannot finalize pack" }
                expected.forEach { wanted ->
                    verify(File(destination, wanted.name), wanted) { progress.verified(it, wanted.name, finalCheck = true) }
                    File(destination, wanted.name).setReadOnly()
                }
                val selected = ActivePack(profile, version)
                if (makeActive) activate(selected) else {
                    write(profilePointer(profile), selected)
                    profileOptOut(profile).delete()
                }
                progress.committed()
                language
            }
        } finally { staging.deleteRecursively() }
    }
    private fun verify(file: File, expected: PackFile, onBytes: (Int) -> Unit = {}) {
        require(file.isFile && file.length() == expected.bytes) { "Missing/wrong-size ${expected.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count); onBytes(count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected.sha256) { "SHA-256 mismatch: ${expected.name}" }
    }
}
