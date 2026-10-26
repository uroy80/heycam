package heycam.feature.settings

import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.data.NewPage
import heycam.core.data.SettingsRepository
import heycam.core.data.StorageUsage
import heycam.core.data.SweepReport
import heycam.core.domain.TextModels
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.FolderId
import heycam.core.model.LibrarySnapshot
import heycam.core.model.LibraryStats
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.Settings
import heycam.core.model.SortOrder
import heycam.core.model.TextState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update

/*
 * Module-local fakes until :core:testing ships the shared ones (PLAN 4.13). Only what the settings screens touch is
 * implemented; anything else fails loudly so a test can never pass by accident.
 */

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    val current: Settings get() = state.value
    var failure: Exception? = null

    override suspend fun update(transform: (Settings) -> Settings) {
        failure?.let { throw it }
        state.update(transform)
    }
}

class FakeFileStore(var usage: StorageUsage = StorageUsage(0, 0, 0)) : FileStore {
    var usageFailure: Exception? = null
    var clearFailure: Exception? = null
    var freedBytes: Long = 0
    var clears = 0
        private set

    override suspend fun usage(): StorageUsage {
        usageFailure?.let { throw it }
        return usage
    }

    override suspend fun clearTemporaryFiles(): SweepReport {
        clears++
        clearFailure?.let { throw it }
        usage = usage.copy(cacheBytes = 0)
        return SweepReport(filesDeleted = if (freedBytes > 0) 1 else 0, bytesFreed = freedBytes)
    }

    override fun newStagedFile(extension: String): File = unused()
    override fun pageDir(fileKey: String): File = unused()
    override fun exportDir(): File = unused()
    override suspend fun sweep(): SweepReport = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by settings")
}

fun trashed(id: Long) = DocumentSummary(
    id = DocumentId(id),
    title = "Trashed $id",
    folderId = null,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
    pageCount = 1,
    storedBytes = 1_000,
    coverThumbPath = null,
    pinned = false,
    trashedAt = Instant.EPOCH,
    textState = TextState.DONE,
)

class FakeDocumentRepository(vararg trash: DocumentSummary) : DocumentRepository {
    private val trash = MutableStateFlow(trash.toList())
    val deleted = mutableListOf<List<DocumentId>>()
    var deleteFailure: Exception? = null

    override fun observeTrash(): Flow<List<DocumentSummary>> = trash

    override suspend fun deleteForever(ids: List<DocumentId>) {
        deleteFailure?.let { throw it }
        deleted += ids
        trash.update { list -> list.filterNot { it.id in ids } }
    }

    override fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot> = unused()
    override fun observeRecent(limit: Int): Flow<List<DocumentSummary>> = unused()
    override fun observeStats(): Flow<LibraryStats> = unused()
    override fun observeDocument(id: DocumentId): Flow<Document?> = unused()
    override fun observeText(id: DocumentId): Flow<List<PageText>> = unused()
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
    override suspend fun purgeTrash(trashedBefore: Instant): Int = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by settings")
}

/** Each [download] hands out a channel the test feeds state by state; closing it ends the flow. */
class FakeTextModels : TextModels {
    val requests = mutableListOf<Set<OcrScript>>()
    var states = Channel<ModuleState>(Channel.UNLIMITED)
        private set
    var failure: Exception? = null

    override fun download(scripts: Set<OcrScript>): Flow<ModuleState> {
        requests += scripts
        failure?.let { throw it }
        return states.consumeAsFlow()
    }

    fun reset() {
        states = Channel(Channel.UNLIMITED)
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
