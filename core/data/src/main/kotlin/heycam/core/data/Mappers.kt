package heycam.core.data

import heycam.core.data.db.DocumentSummaryRow
import heycam.core.data.db.DocumentWithPages
import heycam.core.data.db.FolderRow
import heycam.core.data.db.PageEntity
import heycam.core.data.db.PageTextEntity
import heycam.core.model.Adjustments
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.PageText
import heycam.core.model.SortOrder
import heycam.core.model.TextState
import heycam.core.model.TextStatus
import java.io.File
import java.text.Collator
import java.time.Instant

/** Rebuilds absolute paths from fileKey + file name; the database never stores a path. */
internal class Mappers(private val pageDir: (String) -> File) {

    fun page(e: PageEntity): Page {
        val dir = pageDir(e.fileKey)
        val source = ImageInfo(File(dir, e.sourceFile).absolutePath, e.sourceW, e.sourceH, e.sourceBytes, PageFiles.mimeOf(e.sourceFile))
        val render = e.renderFile?.takeIf { e.renderVersion == e.editsVersion }?.let {
            ImageInfo(File(dir, it).absolutePath, e.renderW, e.renderH, e.renderBytes, PageFiles.mimeOf(it))
        }
        return Page(
            id = PageId(e.id),
            documentId = DocumentId(e.documentId),
            index = e.pageIndex,
            fileKey = e.fileKey,
            source = source,
            edits = edits(e),
            editsVersion = e.editsVersion,
            render = render,
            thumbPath = e.thumbFile?.let { File(dir, it).absolutePath },
            origin = enumOr(e.origin, PageOrigin.SCANNER),
            textStatus = enumOr(e.textStatus, TextStatus.PENDING),
            textScript = enumOrNull<OcrScript>(e.textScript),
        )
    }

    fun summary(r: DocumentSummaryRow) = DocumentSummary(
        id = DocumentId(r.id),
        title = r.title,
        folderId = r.folderId?.let(::FolderId),
        createdAt = Instant.ofEpochMilli(r.createdAt),
        updatedAt = Instant.ofEpochMilli(r.updatedAt),
        pageCount = r.pageCount,
        storedBytes = r.storedBytes,
        coverThumbPath = if (r.coverFileKey != null && r.coverThumbFile != null) File(pageDir(r.coverFileKey), r.coverThumbFile).absolutePath else null,
        pinned = r.pinned,
        trashedAt = r.trashedAt?.let(Instant::ofEpochMilli),
        textState = when {
            r.pendingPages > 0 -> TextState.PENDING
            r.failedPages > 0 -> TextState.FAILED
            r.skippedPages > 0 -> TextState.OFF
            r.donePages > 0 -> TextState.DONE
            else -> TextState.NONE
        },
    )

    fun document(d: DocumentWithPages) = Document(
        id = DocumentId(d.document.id),
        title = d.document.title,
        folderId = d.document.folderId?.let(::FolderId),
        createdAt = Instant.ofEpochMilli(d.document.createdAt),
        updatedAt = Instant.ofEpochMilli(d.document.updatedAt),
        pinned = d.document.pinned,
        trashedAt = d.document.trashedAt?.let(Instant::ofEpochMilli),
        scriptOverride = enumOrNull<OcrScript>(d.document.scriptOverride),
        pages = d.pages.sortedBy { it.pageIndex }.map(::page),
    )

    companion object {
        fun edits(e: PageEntity) = PageEdits(
            rotation = rotationOf(e.rotation),
            filter = enumOr(e.filter, PageFilter.ORIGINAL),
            crop = Codecs.decodeCrop(e.cropJson),
            adjust = Adjustments(e.brightness, e.contrast),
        )

        fun text(e: PageTextEntity) = PageText(
            pageId = PageId(e.pageId),
            script = enumOr(e.script, OcrScript.LATIN),
            text = e.text,
            lines = Codecs.decodeLines(e.linesJson),
            imageWidthPx = e.imageW,
            imageHeightPx = e.imageH,
            editsVersion = e.editsVersion,
            engine = e.engine,
            recognizedAt = Instant.ofEpochMilli(e.recognizedAt),
        )

        fun folder(r: FolderRow) = Folder(
            id = FolderId(r.id),
            name = r.name,
            parentId = r.parentId?.let(::FolderId),
            createdAt = Instant.ofEpochMilli(r.createdAt),
            documentCount = r.documentCount,
        )

        /** Locale-aware, case-insensitive name order (a Collator is not thread-safe, so one per call). */
        fun <T> byName(name: (T) -> String): Comparator<T> {
            val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
            return Comparator { a, b -> collator.compare(name(a), name(b)) }
        }

        fun sortOrder(sort: SortOrder): Comparator<DocumentSummary> = when (sort) {
            SortOrder.UPDATED_DESC -> compareByDescending<DocumentSummary> { it.updatedAt }
            SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
            SortOrder.TITLE_ASC -> byName { it.title }
            SortOrder.SIZE_DESC -> compareByDescending { it.storedBytes }
        }.thenByDescending { it.id.value }
    }
}
