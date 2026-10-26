package heycam.core.model

import java.io.File
import java.time.Instant

/** An image already copied into heycam's own staging area (filesDir/staging), metadata stripped, JPEG. */
data class StagedImage(
    val file: File,
    val widthPx: Int,
    val heightPx: Int,
    val mime: String,
    val origin: PageOrigin,
)

sealed interface ImportTarget {
    data class NewDocument(val title: String? = null, val folderId: FolderId? = null) : ImportTarget
    data class AppendTo(val documentId: DocumentId, val atIndex: Int? = null) : ImportTarget
    data class Replace(val pageId: PageId) : ImportTarget   // retake
}

/** Pages waiting in the review (filters) screen; persisted so they survive process death. */
data class ScanSession(
    val id: String,
    val target: ImportTarget,
    val pages: List<StagedPage>,
    val createdAt: Instant,
)

data class StagedPage(val image: StagedImage, val edits: PageEdits = PageEdits())
