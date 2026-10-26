package heycam.core.testing.export

import android.app.Activity
import android.content.Intent
import heycam.core.export.ExportProgress
import heycam.core.export.ExportRequest
import heycam.core.export.ExportResult
import heycam.core.export.ExportService
import heycam.core.export.ExportTarget
import heycam.core.export.PrintService
import heycam.core.export.SizeEstimate
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.SizePreset
import java.util.Collections
import java.util.Locale

/**
 * [ExportService] for UI tests: records every export and answers with [result] (by default Saved for the picked
 * file or folder, and a share chooser for Share). [estimate] gives [estimates] per preset. File names follow the
 * real rules closely enough for assertions: "<title>.pdf", "<title> - p01.jpg".
 */
class FakeExportService : ExportService {
    data class Export(val request: ExportRequest, val target: ExportTarget)

    private val _exports = Collections.synchronizedList(mutableListOf<Export>())
    val exports: List<Export> get() = synchronized(_exports) { _exports.toList() }

    private val _estimated = Collections.synchronizedList(mutableListOf<ExportRequest>())
    val estimated: List<ExportRequest> get() = synchronized(_estimated) { _estimated.toList() }

    var estimates: Map<SizePreset, Long> = mapOf(SizePreset.SMALL to 180_000L, SizePreset.STANDARD to 420_000L, SizePreset.ORIGINAL to 1_300_000L)

    var result: (ExportRequest, ExportTarget) -> ExportResult = { _, target ->
        when (target) {
            is ExportTarget.Document -> ExportResult.Saved(listOf(target.uri))
            is ExportTarget.Folder -> ExportResult.Saved(listOf(target.treeUri))
            ExportTarget.Share -> ExportResult.ReadyToShare(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf"), null),
            )
        }
    }

    override fun suggestedFileName(title: String, options: ExportOptions, index: Int?): String {
        val base = (options.fileName ?: title).trim().ifEmpty { "heycam" }
        val page = if (index != null) String.format(Locale.ROOT, " - p%02d", index + 1) else ""
        val extension = when (options.format) {
            ExportFormat.PDF -> ".pdf"
            ExportFormat.JPEG -> ".jpg"
            ExportFormat.PNG -> ".png"
            ExportFormat.TEXT -> ".txt"
        }
        return base + page + extension
    }

    override suspend fun estimate(request: ExportRequest): SizeEstimate {
        _estimated += request
        return SizeEstimate(estimates[request.options.size] ?: 0L, measured = true)
    }

    override suspend fun export(request: ExportRequest, target: ExportTarget, onProgress: (ExportProgress) -> Unit): ExportResult {
        _exports += Export(request, target)
        onProgress(ExportProgress(1, 1))
        return result(request, target)
    }
}

/** [PrintService] that records print requests; set [failWith] to make the next ones throw. */
class RecordingPrintService : PrintService {
    private val _printed = Collections.synchronizedList(mutableListOf<ExportRequest>())
    val printed: List<ExportRequest> get() = synchronized(_printed) { _printed.toList() }

    @Volatile var failWith: Exception? = null

    override suspend fun print(activity: Activity, request: ExportRequest) {
        failWith?.let { throw it }
        _printed += request
    }
}
