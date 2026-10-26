package heycam.core.model

import java.time.Instant

data class Folder(
    val id: FolderId,
    val name: String,
    val parentId: FolderId?,          // null = top level; nesting depth <= 3 (repository enforces)
    val createdAt: Instant,
    val documentCount: Int,
)

/** One row in the library. Cheap to load in bulk; never contains page lists or text. */
data class DocumentSummary(
    val id: DocumentId,
    val title: String,
    val folderId: FolderId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pageCount: Int,
    val storedBytes: Long,            // sum of source + render files on disk
    val coverThumbPath: String?,      // absolute path of page 0 thumbnail; null while it renders
    val pinned: Boolean,
    val trashedAt: Instant?,
    val textState: TextState,
)

/**
 * Aggregate of the pages' [TextStatus], first match wins: any PENDING -> PENDING; any FAILED -> FAILED; any SKIPPED
 * -> OFF (auto-text is off); any DONE -> DONE; else NONE (every page was read and none has text, e.g. a photo).
 */
enum class TextState { PENDING, DONE, NONE, FAILED, OFF }

data class Document(
    val id: DocumentId,
    val title: String,
    val folderId: FolderId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pinned: Boolean,
    val trashedAt: Instant?,
    val scriptOverride: OcrScript?,   // null = use Settings.ocrScript
    val pages: List<Page>,            // ordered by index, dense 0..n-1
)

data class Page(
    val id: PageId,
    val documentId: DocumentId,
    val index: Int,
    val fileKey: String,              // UUID; files live in FileStore.pageDir(fileKey)
    val source: ImageInfo,            // as captured/imported; never modified after import
    val edits: PageEdits,
    val editsVersion: Int,            // +1 on every edit; renders and text carry the version they were made for
    val render: ImageInfo?,           // edits applied, full resolution; == source when edits are identity; null while stale
    val thumbPath: String?,
    val origin: PageOrigin,
    val textStatus: TextStatus,
    val textScript: OcrScript?,
)

data class ImageInfo(
    val path: String,                 // absolute
    val widthPx: Int,
    val heightPx: Int,
    val bytes: Long,
    val mime: String,                 // image/jpeg (pages are always stored as JPEG)
)

enum class PageOrigin { SCANNER, PHOTO_PICKER, SHARE, PDF_IMPORT }

data class LibraryStats(val documents: Int, val pages: Int, val storedBytes: Long)

data class LibrarySnapshot(
    val folder: Folder?,              // null = root
    val breadcrumbs: List<Folder>,    // top-level folder first, ending with [folder]; empty at the root
    val folders: List<Folder>,
    val documents: List<DocumentSummary>,
)

enum class SortOrder { UPDATED_DESC, CREATED_DESC, TITLE_ASC, SIZE_DESC }
