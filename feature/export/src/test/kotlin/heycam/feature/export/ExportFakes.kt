package heycam.feature.export

import android.app.Activity
import android.content.Intent
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.NewPage
import heycam.core.data.SettingsRepository
import heycam.core.domain.PageProcessor
import heycam.core.domain.ProcessorState
import heycam.core.export.ExportProgress
import heycam.core.export.ExportRequest
import heycam.core.export.ExportResult
import heycam.core.export.ExportService
import heycam.core.export.ExportTarget
import heycam.core.export.PrintService
import heycam.core.export.SizeEstimate
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.ExportOptions
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.LibrarySnapshot
import heycam.core.model.LibraryStats
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.PageText
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import heycam.core.model.SortOrder
import heycam.core.model.TextStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update

/*
 * Module-local fakes until :core:testing ships the shared ones (PLAN 4.13). Only what the export sheet touches
 * is implemented; anything else fails loudly so a test can never pass by accident.
 */

fun testDocument(
    id: Long,
    title: String = "Lease agreement",
    pages: Int = 2,
    textStatus: TextStatus = TextStatus.DONE,
    trashedAt: Instant? = null,
) = Document(
    id = DocumentId(id),
    title = title,
    folderId = null,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
    pinned = false,
    trashedAt = trashedAt,
    scriptOverride = null,
    pages = List(pages) { i ->
        Page(
            id = PageId(id * 100 + i),
            documentId = DocumentId(id),
            index = i,
            fileKey = "key-$id-$i",
            source = ImageInfo("/pages/key-$id-$i/source.jpg", 2480, 3508, 900_000, "image/jpeg"),
            edits = PageEdits(),
            editsVersion = 0,
            render = null,
            thumbPath = null,
            origin = PageOrigin.SCANNER,
            textStatus = textStatus,
            textScript = OcrScript.LATIN,
        )
    },
)

/** [loaded] false holds every document flow back, as Room does before its first query returns. */
class FakeDocumentRepository(vararg documents: Document) : DocumentRepository {
    private val docs = MutableStateFlow(documents.associateBy { it.id })
    val loaded = MutableStateFlow(true)

    fun put(document: Document) = docs.update { it + (document.id to document) }

    override fun observeDocument(id: DocumentId): Flow<Document?> =
        combine(loaded.filter { it }, docs) { _, all -> all[id] }.distinctUntilChanged()

    override fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot> = unused()
    override fun observeRecent(limit: Int): Flow<List<DocumentSummary>> = unused()
    override fun observeStats(): Flow<LibraryStats> = unused()
    override fun observeText(id: DocumentId): Flow<List<PageText>> = unused()
    override fun observeTrash(): Flow<List<DocumentSummary>> = unused()
    override suspend fun search(query: SearchQuery): List<SearchHit> = unused()
    override suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId = unused()
    override suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int?): List<PageId> = unused()
    override suspend fun replacePage(pageId: PageId, page: NewPage) = unused()
    override suspend fun updateEdits(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits) = unused()
    override suspend fun movePage(documentId: DocumentId, fromIndex: Int, toIndex: Int) = unused()
    override suspend fun deletePages(pageIds: List<PageId>) = unused()
    override suspend fun rename(id: DocumentId, title: String) = unused()
    override suspend fun setPinned(id: DocumentId, pinned: Boolean) = unused()
    override suspend fun setScriptOverride(id: DocumentId, script: OcrScript?) = unused()
    override suspend fun moveToFolder(ids: List<DocumentId>, folderId: FolderId?) = unused()
    override suspend fun trash(ids: List<DocumentId>) = unused()
    override suspend fun restore(ids: List<DocumentId>) = unused()
    override suspend fun deleteForever(ids: List<DocumentId>) = unused()
    override suspend fun purgeTrash(trashedBefore: Instant): Int = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the export sheet")
}

/** [loaded] false holds [settings] back, as DataStore does before its first read. */
class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    val loaded = MutableStateFlow(true)
    override val settings: Flow<Settings> = combine(loaded.filter { it }, state) { _, s -> s }
    val current: Settings get() = state.value
    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)
}

class FakeExportService : ExportService {
    val estimates = mutableListOf<ExportRequest>()
    val exports = mutableListOf<Pair<ExportRequest, ExportTarget>>()
    var result: (ExportRequest, ExportTarget) -> ExportResult = { _, target ->
        when (target) {
            ExportTarget.Share -> ExportResult.ReadyToShare(Intent(Intent.ACTION_CHOOSER))
            is ExportTarget.Document -> ExportResult.Saved(listOf(target.uri))
            is ExportTarget.Folder -> ExportResult.Saved(listOf(target.treeUri, target.treeUri))
        }
    }
    var failure: Exception? = null
    var estimateFailure: Exception? = null

    override fun suggestedFileName(title: String, options: ExportOptions, index: Int?): String =
        (options.fileName ?: title) + options.format.extension

    override suspend fun estimate(request: ExportRequest): SizeEstimate {
        estimates += request
        estimateFailure?.let { throw it }
        val perPage = when (request.options.size) {
            SizePreset.SMALL -> 150_000L
            SizePreset.STANDARD -> 450_000L
            SizePreset.ORIGINAL -> 1_200_000L
        }
        return SizeEstimate(perPage * request.documentIds.size, measured = true)
    }

    override suspend fun export(
        request: ExportRequest,
        target: ExportTarget,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        exports += request to target
        failure?.let { throw it }
        onProgress(ExportProgress(1, 2))
        return result(request, target)
    }

    val discarded = mutableListOf<ExportTarget>()

    override suspend fun discard(target: ExportTarget) {
        discarded += target
    }
}

/** Counts kicks; nothing is ever processed. */
class CountingPageProcessor : PageProcessor {
    override val state: StateFlow<ProcessorState> =
        MutableStateFlow(ProcessorState(pagesToRender = 0, pagesToRead = 0, current = null, models = null))
    var kicks = 0
        private set

    override fun kick() {
        kicks++
    }

    override suspend fun awaitIdle(documentIds: Collection<DocumentId>?) = Unit
    override suspend fun reread(documentId: DocumentId, script: OcrScript?) = Unit
}

class FakePrintService : PrintService {
    val requests = mutableListOf<ExportRequest>()
    var failure: Exception? = null

    override suspend fun print(activity: Activity, request: ExportRequest) {
        requests += request
        failure?.let { throw it }
    }
}

class RecordingLog : HeycamLog {
    val lines = mutableListOf<String>()
    override fun d(tag: String, message: String) {
        lines += "D/$tag $message"
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        lines += "W/$tag $message"
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        lines += "E/$tag $message"
    }
}
