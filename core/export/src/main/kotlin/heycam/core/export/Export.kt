package heycam.core.export

import android.app.Activity
import android.content.Intent
import android.net.Uri
import heycam.core.model.DocumentId
import heycam.core.model.ExportOptions

data class ExportRequest(
    val documentIds: List<DocumentId>,    // one output per document (merge is v1.1)
    val options: ExportOptions,
    val whenTextPending: TextPendingPolicy = TextPendingPolicy.ASK,
)

enum class TextPendingPolicy { ASK, WAIT, WITHOUT_TEXT }

sealed interface ExportTarget {
    data class Document(val uri: Uri) : ExportTarget       // from ACTION_CREATE_DOCUMENT
    data class Folder(val treeUri: Uri) : ExportTarget     // from ACTION_OPEN_DOCUMENT_TREE (several files)
    data object Share : ExportTarget                       // cacheDir/exports + FileProvider
}

sealed interface ExportResult {
    data class Saved(val uris: List<Uri>) : ExportResult
    data class ReadyToShare(val chooser: Intent) : ExportResult   // FLAG_GRANT_READ_URI_PERMISSION set
    data class TextPending(val pagesPending: Int) : ExportResult  // only with TextPendingPolicy.ASK
    data class Failed(val reason: ExportFailure) : ExportResult
}

enum class ExportFailure { NO_SPACE, WRITE_FAILED, SOURCE_MISSING, CANCELLED }

data class ExportProgress(val done: Int, val total: Int)

data class SizeEstimate(val bytes: Long, val measured: Boolean)  // measured = one page encoded, the rest sized by pixels

interface ExportService {
    fun suggestedFileName(title: String, options: ExportOptions, index: Int? = null): String
    suspend fun estimate(request: ExportRequest): SizeEstimate
    suspend fun export(request: ExportRequest, target: ExportTarget, onProgress: (ExportProgress) -> Unit = {}): ExportResult
    /**
     * Removes the empty file a "Save to" picker made for an export that will not run after all (the text question
     * was dismissed). [export] already removes it on a failure or a cancel.
     */
    suspend fun discard(target: ExportTarget) = Unit
}

/** Framework PrintManager with a PrintDocumentAdapter over a PDF built by ExportService. */
interface PrintService {
    suspend fun print(activity: Activity, request: ExportRequest)
}
