package org.itantra.app.core

enum class ImportStage(val label: String) {
    READING("Reading pack details"),
    COPYING("Copying model files"),
    VERIFYING("Verifying SHA-256"),
    FINALIZING("Checking installed files"),
    IMPORTED("Files imported"),
    LOADING("Loading speech model"),
    READY("Ready to use"),
    FAILED("Setup could not finish")
}

data class PackImportProgress(
    val language: LanguageCode? = null,
    val stage: ImportStage = ImportStage.READING,
    val percent: Int? = null,
    val copiedBytes: Long = 0,
    val modelBytes: Long = 0,
    val file: String? = null,
    val error: String? = null
)

/** One copy pass + the importer's two checksum passes. Never guesses elapsed time.
 * 100% is emitted only after the active-pack pointer has been committed.
 */
class ImportProgressTracker(
    private val language: LanguageCode,
    private val modelBytes: Long,
    private val emit: (PackImportProgress) -> Unit
) {
    private val workBytes: Long
    private var processed = 0L
    private var copied = 0L
    private var last: PackImportProgress? = null
    init {
        require(modelBytes > 0 && modelBytes <= Long.MAX_VALUE / 300)
        workBytes = modelBytes * 3
        publish(ImportStage.COPYING, null)
    }
    fun copied(count: Int, file: String) {
        require(count >= 0 && copied + count <= modelBytes)
        copied += count
        advance(count, ImportStage.COPYING, file)
    }
    fun verified(count: Int, file: String, finalCheck: Boolean = false) =
        advance(count, if (finalCheck) ImportStage.FINALIZING else ImportStage.VERIFYING, file)

    private fun advance(count: Int, stage: ImportStage, file: String) {
        require(count >= 0 && processed + count <= workBytes)
        processed += count
        publish(stage, file)
    }
    private fun publish(stage: ImportStage, file: String?, complete: Boolean = false) {
        val percent = if (complete) 100 else (processed * 100 / workBytes).toInt().coerceAtMost(99)
        val next = PackImportProgress(language, stage, percent, copied, modelBytes, file)
        // Bound UI updates by real percentage changes, stage transitions and filenames.
        if (last?.percent != percent || last?.stage != stage || last?.file != file) {
            last = next
            emit(next)
        }
    }
    fun committed() {
        check(processed == workBytes && copied == modelBytes)
        publish(ImportStage.IMPORTED, null, complete = true)
    }
}
