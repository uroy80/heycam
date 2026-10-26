package heycam.feature.library

import heycam.core.data.DocumentRepository
import heycam.core.data.FolderRepository
import heycam.core.data.NewPage
import heycam.core.domain.PageProcessor
import heycam.core.domain.ProcessorState
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.LibrarySnapshot
import heycam.core.model.LibraryStats
import heycam.core.model.OcrScript
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.SortOrder
import heycam.core.model.TextState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

fun summary(
    id: Long,
    title: String,
    folder: Long? = null,
    updated: Long = id,
    pinned: Boolean = false,
    trashed: Boolean = false,
    pages: Int = 2,
    bytes: Long = 100_000,
) = DocumentSummary(
    id = DocumentId(id), title = title, folderId = folder?.let(::FolderId),
    createdAt = Instant.ofEpochSecond(updated * 1000), updatedAt = Instant.ofEpochSecond(updated * 1000),
    pageCount = pages, storedBytes = bytes, coverThumbPath = null, pinned = pinned,
    trashedAt = if (trashed) Instant.ofEpochSecond(updated * 1000 + 10) else null, textState = TextState.DONE,
)

fun folder(id: Long, name: String, parent: Long? = null, count: Int = 0) =
    Folder(FolderId(id), name, parent?.let(::FolderId), Instant.EPOCH, count)

/** In-memory library: the same rules as the real repository, minus storage. */
class FakeLibrary(docs: List<DocumentSummary> = emptyList(), folders: List<Folder> = emptyList()) : DocumentRepository, FolderRepository {
    val docs = MutableStateFlow(docs)
    val folders = MutableStateFlow(folders)
    var searchHits: List<SearchHit>? = null
    var failSearch = false
    var failWrites = false
    val searches = mutableListOf<SearchQuery>()
    val deletedFolders = mutableListOf<Pair<FolderId, FolderId?>>()
    private var nextFolder = 100L

    private fun sorted(list: List<DocumentSummary>, sort: SortOrder) = when (sort) {
        SortOrder.UPDATED_DESC -> list.sortedByDescending { it.updatedAt }
        SortOrder.CREATED_DESC -> list.sortedByDescending { it.createdAt }
        SortOrder.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
        SortOrder.SIZE_DESC -> list.sortedByDescending { it.storedBytes }
    }

    override fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot> = combine(docs, folders) { d, f ->
        val here = folderId?.let { id -> f.firstOrNull { it.id == id } }
        val crumbs = generateSequence(here) { c -> c.parentId?.let { p -> f.firstOrNull { it.id == p } } }.toList().reversed()
        LibrarySnapshot(
            folder = here,
            breadcrumbs = crumbs,
            folders = if (folderId != null && here == null) emptyList() else f.filter { it.parentId == folderId },
            documents = sorted(d.filter { it.folderId == folderId && it.trashedAt == null }, sort),
        )
    }

    override fun observeRecent(limit: Int): Flow<List<DocumentSummary>> =
        docs.map { d -> d.filter { it.trashedAt == null }.sortedByDescending { it.updatedAt }.take(limit) }

    override fun observeStats(): Flow<LibraryStats> = docs.map { d ->
        val live = d.filter { it.trashedAt == null }
        LibraryStats(live.size, live.sumOf { it.pageCount }, live.sumOf { it.storedBytes })
    }

    override fun observeTrash(): Flow<List<DocumentSummary>> = docs.map { d -> d.filter { it.trashedAt != null } }

    override suspend fun search(query: SearchQuery): List<SearchHit> {
        searches += query
        if (failSearch) error("search failed")
        searchHits?.let { return it }
        return docs.value.filter { it.trashedAt == null }.mapNotNull { d ->
            val at = d.title.indexOf(query.text, ignoreCase = true)
            if (at < 0) null else SearchHit.TitleHit(d, at until at + query.text.length)
        }
    }

    private fun write(ids: List<DocumentId>, transform: (DocumentSummary) -> DocumentSummary) {
        check(!failWrites) { "write failed" }
        docs.update { list -> list.map { if (it.id in ids) transform(it) else it } }
    }

    override suspend fun rename(id: DocumentId, title: String) = write(listOf(id)) { it.copy(title = title) }
    override suspend fun setPinned(id: DocumentId, pinned: Boolean) = write(listOf(id)) { it.copy(pinned = pinned) }
    override suspend fun moveToFolder(ids: List<DocumentId>, folderId: FolderId?) = write(ids) { it.copy(folderId = folderId) }
    override suspend fun trash(ids: List<DocumentId>) = write(ids) { it.copy(trashedAt = Instant.EPOCH) }
    override suspend fun restore(ids: List<DocumentId>) = write(ids) { it.copy(trashedAt = null) }
    override suspend fun deleteForever(ids: List<DocumentId>) {
        check(!failWrites) { "write failed" }
        docs.update { list -> list.filter { it.id !in ids } }
    }

    // FolderRepository
    override fun observeFolders(parentId: FolderId?): Flow<List<Folder>> = folders.map { f -> f.filter { it.parentId == parentId } }
    override suspend fun create(name: String, parentId: FolderId?): FolderId {
        val id = FolderId(nextFolder++)
        folders.update { it + Folder(id, name, parentId, Instant.EPOCH, 0) }
        return id
    }
    override suspend fun rename(id: FolderId, name: String) = folders.update { list -> list.map { if (it.id == id) it.copy(name = name) else it } }
    override suspend fun delete(id: FolderId, moveDocumentsTo: FolderId?) {
        deletedFolders += id to moveDocumentsTo
        folders.update { list -> list.filter { it.id != id } }
        docs.update { list -> list.map { if (it.folderId == id) it.copy(folderId = moveDocumentsTo) else it } }
    }

    // Not used by the library screens.
    override fun observeDocument(id: DocumentId): Flow<Document?> = throw UnsupportedOperationException()
    override fun observeText(id: DocumentId): Flow<List<PageText>> = throw UnsupportedOperationException()
    override suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId = throw UnsupportedOperationException()
    override suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int?): List<PageId> = throw UnsupportedOperationException()
    override suspend fun replacePage(pageId: PageId, page: NewPage) = throw UnsupportedOperationException()
    override suspend fun updateEdits(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits) = throw UnsupportedOperationException()
    override suspend fun movePage(documentId: DocumentId, fromIndex: Int, toIndex: Int) = throw UnsupportedOperationException()
    override suspend fun deletePages(pageIds: List<PageId>) = throw UnsupportedOperationException()
    override suspend fun setScriptOverride(id: DocumentId, script: OcrScript?) = throw UnsupportedOperationException()
    override suspend fun purgeTrash(trashedBefore: Instant): Int = throw UnsupportedOperationException()
}

/** Counts wake-ups; the library screens never wait on it. */
class FakePageProcessor : PageProcessor {
    override val state: StateFlow<ProcessorState> = MutableStateFlow(ProcessorState(0, 0, null, null))
    var kicks = 0
    override fun kick() { kicks++ }
    override suspend fun awaitIdle(documentIds: Collection<DocumentId>?) = Unit
    override suspend fun reread(documentId: DocumentId, script: OcrScript?) = Unit
}
