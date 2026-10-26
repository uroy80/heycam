package heycam.feature.document

import android.graphics.Bitmap
import heycam.core.data.DocumentRepository
import heycam.core.data.FolderRepository
import heycam.core.data.NewPage
import heycam.core.data.PageTextEditor
import heycam.core.data.PageWorkStore
import heycam.core.domain.PageProcessor
import heycam.core.domain.ProcessorState
import heycam.core.export.ExportRequest
import heycam.core.export.ExportResult
import heycam.core.export.ExportService
import heycam.core.export.ExportTarget
import heycam.core.export.ExportProgress
import heycam.core.export.SizeEstimate
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RenderSpec
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.ExportOptions
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
import heycam.core.model.SortOrder
import heycam.core.model.TextStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun page(id: Long, index: Int, doc: Long = 7, status: TextStatus = TextStatus.DONE, edits: PageEdits = PageEdits()): Page {
    val source = ImageInfo("/pages/k$id/source.jpg", 2480, 3508, 1_000_000, "image/jpeg")
    return Page(
        id = PageId(id), documentId = DocumentId(doc), index = index, fileKey = "k$id", source = source, edits = edits,
        editsVersion = 1, render = source, thumbPath = null, origin = PageOrigin.SCANNER, textStatus = status, textScript = OcrScript.LATIN,
    )
}

fun document(id: Long = 7, title: String = "Lease agreement", pages: List<Page>) = Document(
    id = DocumentId(id), title = title, folderId = null, createdAt = Instant.parse("2026-10-20T09:00:00Z"),
    updatedAt = Instant.parse("2026-10-20T09:00:00Z"), pinned = false, trashedAt = null, scriptOverride = null, pages = pages,
)

fun pageText(pageId: Long, text: String, editsVersion: Int = 1) = PageText(
    pageId = PageId(pageId), script = OcrScript.LATIN, text = text, lines = emptyList(), imageWidthPx = 2480, imageHeightPx = 3508,
    editsVersion = editsVersion, engine = "test", recognizedAt = Instant.EPOCH,
)

/** In-memory documents; records what the screens ask for. Like the real repository it is also the text editor. */
class FakeDocumentRepository(vararg initial: Document) : DocumentRepository, PageTextEditor {
    val documents = MutableStateFlow(initial.associateBy { it.id })
    val texts = MutableStateFlow<Map<DocumentId, List<PageText>>>(emptyMap())
    val moves = mutableListOf<Triple<DocumentId, Int, Int>>()
    val deleted = mutableListOf<List<PageId>>()
    val edited = mutableListOf<List<PageId>>()
    var failWrites = false

    override fun observeDocument(id: DocumentId): Flow<Document?> = documents.map { it[id] }
    override fun observeText(id: DocumentId): Flow<List<PageText>> = texts.map { it[id].orEmpty() }

    override suspend fun movePage(documentId: DocumentId, fromIndex: Int, toIndex: Int) {
        check(!failWrites)
        moves += Triple(documentId, fromIndex, toIndex)
        updateDoc(documentId) { doc ->
            val pages = doc.pages.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            doc.copy(pages = pages.mapIndexed { i, p -> p.copy(index = i) })
        }
    }

    override suspend fun updateEdits(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits) {
        check(!failWrites)
        edited += pageIds
        documents.update { all ->
            all.mapValues { (_, doc) ->
                doc.copy(pages = doc.pages.map { p -> if (p.id in pageIds) p.copy(edits = transform(p.edits), editsVersion = p.editsVersion + 1) else p })
            }
        }
    }

    override suspend fun deletePages(pageIds: List<PageId>) {
        check(!failWrites)
        deleted += pageIds
        documents.update { all ->
            all.mapValues { (_, doc) -> doc.copy(pages = doc.pages.filter { it.id !in pageIds }.mapIndexed { i, p -> p.copy(index = i) }) }
        }
    }

    /** Replaces the page's text whatever has happened to the page since it was read, as the real editor does. */
    override suspend fun updateText(pageId: PageId, text: String) {
        check(!failWrites)
        val docId = documents.value.values.firstOrNull { d -> d.pages.any { it.id == pageId } }?.id
            ?: throw NoSuchElementException("page ${pageId.value}")
        texts.update { all -> all + (docId to all[docId].orEmpty().map { if (it.pageId == pageId) it.copy(text = text) else it }) }
    }

    override suspend fun rename(id: DocumentId, title: String) = updateDoc(id) { it.copy(title = title) }
    override suspend fun setPinned(id: DocumentId, pinned: Boolean) = updateDoc(id) { it.copy(pinned = pinned) }
    override suspend fun setScriptOverride(id: DocumentId, script: OcrScript?) = updateDoc(id) { it.copy(scriptOverride = script) }
    override suspend fun moveToFolder(ids: List<DocumentId>, folderId: FolderId?) = ids.forEach { id -> updateDoc(id) { it.copy(folderId = folderId) } }
    override suspend fun trash(ids: List<DocumentId>) = ids.forEach { id -> updateDoc(id) { it.copy(trashedAt = Instant.EPOCH) } }
    override suspend fun restore(ids: List<DocumentId>) = ids.forEach { id -> updateDoc(id) { it.copy(trashedAt = null) } }

    private fun updateDoc(id: DocumentId, transform: (Document) -> Document) {
        check(!failWrites)
        documents.update { all -> all[id]?.let { all + (id to transform(it)) } ?: all }
    }

    // Library queries are not used by the document screens.
    override fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot> = throw UnsupportedOperationException()
    override fun observeRecent(limit: Int): Flow<List<DocumentSummary>> = throw UnsupportedOperationException()
    override fun observeStats(): Flow<LibraryStats> = throw UnsupportedOperationException()
    override fun observeTrash(): Flow<List<DocumentSummary>> = throw UnsupportedOperationException()
    override suspend fun search(query: SearchQuery): List<SearchHit> = throw UnsupportedOperationException()
    override suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId = throw UnsupportedOperationException()
    override suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int?): List<PageId> = throw UnsupportedOperationException()
    override suspend fun replacePage(pageId: PageId, page: NewPage) = throw UnsupportedOperationException()
    override suspend fun deleteForever(ids: List<DocumentId>) = throw UnsupportedOperationException()
    override suspend fun purgeTrash(trashedBefore: Instant): Int = throw UnsupportedOperationException()
}

class FakeFolderRepository(private val folders: List<Folder> = emptyList()) : FolderRepository {
    override fun observeFolders(parentId: FolderId?): Flow<List<Folder>> = MutableStateFlow(folders.filter { it.parentId == parentId })
    override suspend fun create(name: String, parentId: FolderId?): FolderId = throw UnsupportedOperationException()
    override suspend fun rename(id: FolderId, name: String) = throw UnsupportedOperationException()
    override suspend fun delete(id: FolderId, moveDocumentsTo: FolderId?) = throw UnsupportedOperationException()
}

class FakePageProcessor : PageProcessor {
    val mutableState = MutableStateFlow(ProcessorState(0, 0, null, null))
    override val state: StateFlow<ProcessorState> = mutableState
    var kicks = 0
    var retries = 0
    val rereads = mutableListOf<Pair<DocumentId, OcrScript?>>()
    override fun kick() { kicks++ }
    override fun retry() { retries++; kick() }
    override suspend fun awaitIdle(documentIds: Collection<DocumentId>?) = Unit
    override suspend fun reread(documentId: DocumentId, script: OcrScript?) { rereads += documentId to script }
}

class FakePageWorkStore : PageWorkStore {
    val savedTexts = mutableListOf<PageText>()
    val statuses = mutableListOf<Triple<PageId, Int, TextStatus>>()
    override suspend fun saveText(text: PageText): Boolean {
        savedTexts += text
        return true
    }
    override suspend fun setTextStatus(pageId: PageId, editsVersion: Int, status: TextStatus): Boolean {
        statuses += Triple(pageId, editsVersion, status)
        return true
    }
    override suspend fun pagesNeedingRender(limit: Int): List<Page> = emptyList()
    override suspend fun pagesNeedingText(limit: Int): List<Page> = emptyList()
    override suspend fun saveRender(pageId: PageId, editsVersion: Int, render: ImageInfo, thumbPath: String): Boolean = true
    override suspend fun markTextPending(documentId: DocumentId) = Unit
}

class FakeExportService : ExportService {
    val results = ArrayDeque<ExportResult>()
    val requests = mutableListOf<Pair<ExportRequest, ExportTarget>>()
    override fun suggestedFileName(title: String, options: ExportOptions, index: Int?): String = "$title.txt"
    override suspend fun estimate(request: ExportRequest): SizeEstimate = SizeEstimate(0, measured = false)
    override suspend fun export(request: ExportRequest, target: ExportTarget, onProgress: (ExportProgress) -> Unit): ExportResult {
        requests += request to target
        return results.removeFirst()
    }
}

class FakePageRenderer : PageRenderer {
    var previews = 0
    override suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo = source
    override suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap {
        previews++
        return Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888)
    }
    override suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int): File = out
}
