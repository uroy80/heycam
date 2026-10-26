package heycam.core.data

import heycam.core.common.StagingArea
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.ImportTarget
import heycam.core.model.LibrarySnapshot
import heycam.core.model.LibraryStats
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.ScanSession
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.Settings
import heycam.core.model.SortOrder
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.model.TextStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** A staged image plus its initial edits; the repository moves the file into FileStore inside the same transaction. */
typealias NewPage = StagedPage

/**
 * Library, documents and pages. Missing ids in mutations: single-id writes that need the row ([addPages],
 * [replacePage], [movePage]) throw [NoSuchElementException]; bulk writes skip ids that are gone.
 * Blank titles throw [IllegalArgumentException].
 */
interface DocumentRepository {
    /** The folder's direct sub-folders and non-trashed documents (null = root). An unknown folder emits an empty snapshot. */
    fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot>
    fun observeRecent(limit: Int = 5): Flow<List<DocumentSummary>>
    fun observeStats(): Flow<LibraryStats>
    fun observeDocument(id: DocumentId): Flow<Document?>
    fun observeText(id: DocumentId): Flow<List<PageText>>
    /** Most recently trashed first. */
    fun observeTrash(): Flow<List<DocumentSummary>>

    /**
     * Titles: case-insensitive substring. Text: FTS5 trigram for >= 3 code points, instr() fallback below that. NFC on both sides.
     * Title hits come first, then one text hit per matching page; each group holds at most [SearchQuery.limit] hits.
     * A folder scope includes its sub-folders.
     */
    suspend fun search(query: SearchQuery): List<SearchHit>

    /**
     * Moves each staged file into its page directory; a folder deleted meanwhile files the document at the root.
     * Here and in [addPages] and [replacePage], a call cancelled while it waits for the storage lock saves nothing;
     * cancelled after that, it still finishes the save (rows and files together), then throws CancellationException.
     */
    suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId
    suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int? = null): List<PageId>
    /** Retake: same page id and position, new source, edits from [page], text read again. */
    suspend fun replacePage(pageId: PageId, page: NewPage)
    /** Bumps editsVersion of every page whose edits change; rotation or crop changes mark its text PENDING. */
    suspend fun updateEdits(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits)
    suspend fun movePage(documentId: DocumentId, fromIndex: Int, toIndex: Int)
    /** Hard delete (rows + files). The UI offers Undo by delaying this call until its snackbar times out. */
    suspend fun deletePages(pageIds: List<PageId>)
    suspend fun rename(id: DocumentId, title: String)
    suspend fun setPinned(id: DocumentId, pinned: Boolean)
    suspend fun setScriptOverride(id: DocumentId, script: OcrScript?)
    suspend fun moveToFolder(ids: List<DocumentId>, folderId: FolderId?)
    suspend fun trash(ids: List<DocumentId>)
    suspend fun restore(ids: List<DocumentId>)
    suspend fun deleteForever(ids: List<DocumentId>)
    suspend fun purgeTrash(trashedBefore: Instant): Int
}

/**
 * The user's correction of a page's recognised text (Text view, F7). Bound to the same instance as
 * [DocumentRepository]; separate so existing [DocumentRepository] implementations stay source-compatible.
 */
interface PageTextEditor {
    /**
     * Replaces the page's text for search and TXT export; the line geometry used by the PDF text layer is kept.
     * Blank text marks the page EMPTY. Throws [NoSuchElementException] for an unknown page.
     */
    suspend fun updateText(pageId: PageId, text: String)
}

/**
 * Bookkeeping used only by :core:domain's PageProcessor. Writes are rejected (false) when editsVersion is stale.
 * Pages of trashed documents are not offered. Text work is offered only once the page's render is current.
 */
interface PageWorkStore {
    suspend fun pagesNeedingRender(limit: Int): List<Page>
    suspend fun pagesNeedingText(limit: Int): List<Page>
    /** [render] and [thumbPath] must be files inside the page's directory (render may be the source itself). */
    suspend fun saveRender(pageId: PageId, editsVersion: Int, render: ImageInfo, thumbPath: String): Boolean
    /** Stores NFC text and marks the page DONE, or EMPTY when no text was found. */
    suspend fun saveText(text: PageText): Boolean
    suspend fun setTextStatus(pageId: PageId, editsVersion: Int, status: TextStatus): Boolean
    suspend fun markTextPending(documentId: DocumentId)
}

interface FolderRepository {
    /** Sorted by name. */
    fun observeFolders(parentId: FolderId?): Flow<List<Folder>>
    /**
     * Throws [FolderTooDeepException] below [MAX_DEPTH], [NoSuchElementException] for an unknown parent, and
     * [FolderNameInUseException] when a folder beside it already has the name, ignoring case.
     */
    suspend fun create(name: String, parentId: FolderId?): FolderId
    /** Throws [FolderNameInUseException] when another folder beside it already has the name, ignoring case. */
    suspend fun rename(id: FolderId, name: String)
    /** Documents inside move to [moveDocumentsTo] (null = root); sub-folders move up one level. */
    suspend fun delete(id: FolderId, moveDocumentsTo: FolderId?)

    companion object {
        /** Top-level folders are depth 1. */
        const val MAX_DEPTH = 3
    }
}

class FolderTooDeepException : IllegalArgumentException("folders nest at most ${FolderRepository.MAX_DEPTH} deep")

class FolderNameInUseException : IllegalArgumentException("a folder there already has that name")

/** Two folder names clash when they match ignoring case (after the name is cleaned and NFC-normalised). */
internal fun folderNameKey(cleanName: String): String = cleanName.lowercase()

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

/**
 * Review-screen sessions, persisted as JSON in filesDir/staging/<id>/session.json.
 * [create] moves loose staged files into the session directory (use the returned session's files) and starts every
 * page on Settings.defaultFilter. A session untouched for 24 h is swept.
 */
interface ScanSessionStore {
    suspend fun create(target: ImportTarget, images: List<StagedImage>): ScanSession
    /** Null when unknown or swept; pages whose file is gone are dropped. */
    suspend fun get(id: String): ScanSession?
    /** Saves pages and edits; staged files of pages no longer in the session are deleted. */
    suspend fun update(session: ScanSession)
    suspend fun discard(id: String)          // deletes staged files
}

interface FileStore : StagingArea {
    /**
     * filesDir/pages/<fileKey>/: source.jpg plus the render and thumbnail PageProcessor writes there (a new name per
     * editsVersion; [PageWorkStore.saveRender] deletes the ones it replaces). Throws for anything but a file key.
     */
    fun pageDir(fileKey: String): File
    fun exportDir(): File                     // cacheDir/exports/  (FileProvider root for sharing)
    /**
     * Removes page directories no page owns, files in page directories that their page does not use (after 24 h),
     * staging untouched for 24 h (a live session keeps its own files and the loose files it refers to), exports
     * older than 24 h and ML Kit's mlkit_docscan_ui_client leftovers older than 24 h.
     */
    suspend fun sweep(): SweepReport
    suspend fun usage(): StorageUsage
    /**
     * Settings "Clear temporary files": all exports and ML Kit scanner files, plus what [sweep] removes. Review
     * sessions stay. The default suits stores that keep no other temporary files.
     */
    suspend fun clearTemporaryFiles(): SweepReport = sweep()
}

data class SweepReport(val filesDeleted: Int, val bytesFreed: Long)
data class StorageUsage(val pagesBytes: Long, val trashBytes: Long, val cacheBytes: Long)
