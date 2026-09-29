package org.itantra.app.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.itantra.app.core.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class CatalogueModel(val language: LanguageCode, val packId: String, val filename: String,
    val bytes: Long, val installedBytes: Long, val sha256: String, val url: String?)
data class ModelDownloadState(val language: LanguageCode? = null, val phase: String = "", val bytes: Long = 0,
    val total: Long = 0, val filename: String = "", val active: Boolean = false, val installing: Boolean = false,
    val detail: String = "", val partials: Map<LanguageCode, Long> = emptyMap())

/** User-initiated, foreground-only HTTPS downloads. Disk partials survive process death.
 * No endpoint, path, model identity or hash can be supplied by a downloaded manifest.
 * Large files are streamed; only the existing allowlisted importer can activate them. */
class ModelDownloads(private val context: Context, private val scope: CoroutineScope,
    private val wifiOnly: () -> Boolean, private val install: suspend (File) -> Unit) {
    private val directory = File(context.noBackupFilesDir, "model-downloads")
    val catalogue: List<CatalogueModel> by lazy {
        val source = JSONObject(context.assets.open("model-catalogue.json").bufferedReader().use { it.readText() })
        require(source.getInt("schemaVersion") == 1)
        val entries = source.getJSONArray("entries")
        (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val language = LanguageCode.fromCode(item.getString("language"))
            val model = CatalogueModel(language, item.getString("packId"), item.getString("file"),
                item.getLong("bytes"), item.getLong("installedBytes"), item.getString("sha256"),
                if (item.isNull("url")) null else item.getString("url"))
            require(model.packId == PackCatalog.preferred(language).id && model.bytes > 0 && model.installedBytes > 0)
            require(model.sha256.matches(Regex("[a-f0-9]{64}")))
            require(model.url == null || DownloadRules.https(model.url))
            model
        }.also { require(it.map { entry -> entry.language }.distinct().size == it.size) }
    }
    private val mutable = MutableStateFlow(ModelDownloadState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var pauseRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var foreground = true
    init { scope.launch(Dispatchers.IO) { check(directory.isDirectory || directory.mkdirs()); refreshPartials() } }
    private fun partial(model: CatalogueModel) = File(directory, model.sha256 + ".part")
    private fun complete(model: CatalogueModel) = File(directory, model.sha256 + ".verified")
    private fun refreshPartials() {
        mutable.update { it.copy(partials = catalogue.associate { model -> model.language to
            maxOf(partial(model).length(), complete(model).length()) }.filterValues { bytes -> bytes > 0 }) }
    }
    fun setForeground(value: Boolean) { foreground = value; if (!value) pause() }
    fun pause() {
        if (mutable.value.installing || !mutable.value.active) return
        pauseRequested = true
        // Disconnect off the UI thread; read/connect timeouts also bound vendor implementations.
        scope.launch(Dispatchers.IO) { runCatching { connection?.disconnect() } }
    }
    fun cancel(language: LanguageCode) {
        if (mutable.value.installing) return
        if (mutable.value.active && mutable.value.language == language) {
            cancelRequested = true; pause(); return
        }
        if (mutable.value.active) return
        scope.launch(Dispatchers.IO) {
            catalogue.singleOrNull { it.language == language }?.let { model ->
                partial(model).delete(); complete(model).delete()
                mutable.update { it.copy(phase = "Cancelled", detail = "Temporary download removed. Installed models were not changed.") }
                refreshPartials()
            }
        }
    }
    fun start(language: LanguageCode) {
        if (job?.isActive == true || !foreground) return
        val model = catalogue.singleOrNull { it.language == language } ?: return
        if (model.url == null) {
            mutable.update { it.copy(language = language, phase = "Import available", detail = "A verified download location has not been published. Use .itpack import or USB.") }; return
        }
        pauseRequested = false; cancelRequested = false
        mutable.value = ModelDownloadState(language, "Preparing", total = model.bytes, filename = model.filename,
            active = true, partials = mutable.value.partials)
        job = scope.launch(Dispatchers.IO) {
            try {
                check(directory.isDirectory || directory.mkdirs())
                val part = partial(model)
                val ready = complete(model)
                require(part.length() <= model.bytes) { "Partial download is invalid. Cancel and retry." }
                require(directory.usableSpace >= DownloadRules.requiredSpace(model.bytes, maxOf(part.length(), ready.length()), model.installedBytes)) {
                    "Not enough free space for the download and safe installation. Existing models are kept."
                }
                if (!ready.isFile) {
                    if (part.length() < model.bytes) download(model, part)
                    checkPaused()
                    mutable.update { it.copy(phase = "Verifying SHA-256", bytes = model.bytes) }
                    verify(part, model)
                    check(part.renameTo(ready)) { "Cannot finalise verified download." }
                } else verify(ready, model) // Never trust the filename after a restart.
                checkPaused()
                mutable.update { it.copy(phase = "Installing verified model", installing = true) }
                install(ready)
                ready.delete()
                mutable.update { it.copy(phase = "Installed", detail = "File integrity verified. Accuracy is not a validation score.") }
            } catch (error: Exception) {
                mutable.update { it.copy(phase = if (pauseRequested || !foreground) "Paused" else "Action needed",
                    detail = if (pauseRequested || !foreground) "Open Models and tap Resume. Downloaded bytes are kept."
                        else error.message?.take(200) ?: "Download failed. Retry or use offline import.") }
            } finally {
                runCatching { connection?.disconnect() }; connection = null
                if (cancelRequested) {
                    partial(model).delete(); complete(model).delete()
                    mutable.update { it.copy(phase = "Cancelled", bytes = 0, detail = "Temporary download removed. Installed models were not changed.") }
                }
                mutable.update { it.copy(active = false, installing = false) }
                refreshPartials()
            }
        }
    }
    private fun checkPaused() { check(!pauseRequested && foreground) { "Download paused." } }
    private fun online() {
        checkPaused()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        check(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) { "Internet is unavailable. Resume after connecting or use USB import." }
        check(!wifiOnly() || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { "Wi-Fi-only is enabled. Connect to Wi-Fi, then tap Resume." }
    }
    private fun download(model: CatalogueModel, output: File) {
        online()
        val offset = output.length()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(manager.activeNetwork) { "Internet is unavailable." }
        require(!wifiOnly() || manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) { "Connect to Wi-Fi and resume." }
        val request = network.openConnection(URL(requireNotNull(model.url))) as HttpURLConnection
        connection = request
        request.instanceFollowRedirects = false // A release must pin its final, approved HTTPS location.
        request.connectTimeout = 15000; request.readTimeout = 15000
        request.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) request.setRequestProperty("Range", "bytes=$offset-")
        val start = DownloadRules.rangeStart(request.responseCode, request.getHeaderField("Content-Range"), offset, model.bytes)
        require(request.contentEncoding == null || request.contentEncoding.equals("identity", true)) { "Compressed HTTP responses are not supported. Use offline import." }
        val size = request.contentLengthLong
        require(size < 0 || size == model.bytes - start) { "Download size differs from the pinned catalogue." }
        // If the server ignored Range, space must also cover replacing that partial.
        require(directory.usableSpace + output.length() >= DownloadRules.requiredSpace(model.bytes, 0, model.installedBytes)) { "Not enough storage." }
        mutable.update { it.copy(phase = "Downloading", bytes = start) }
        var copied = start
        var checkedAt = 0L
        request.inputStream.buffered().use { input ->
            FileOutputStream(output, start > 0).use { sink ->
                try {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkPaused()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - checkedAt >= 500) { online(); checkedAt = now }
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(copied + count <= model.bytes) { "Server exceeded the pinned download size." }
                        sink.write(buffer, 0, count); copied += count
                        mutable.update { it.copy(bytes = copied) }
                    }
                } finally { sink.fd.sync() }
            }
        }
        require(copied == model.bytes) { "Download interrupted. Tap Resume to continue." }
    }
    private fun verify(file: File, model: CatalogueModel) {
        require(file.length() == model.bytes) { "Download is incomplete. Cancel and retry." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { checkPaused(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == model.sha256) {
            "SHA-256 verification failed. Nothing was installed. Cancel this download and retry."
        }
    }
}
