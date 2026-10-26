package heycam.core.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

// Schema v1 (PLAN 3.6). Times are epoch milliseconds. Enums are stored by name. No absolute paths: page files are
// found through fileKey plus the file names below, so device-to-device transfer and restore keep working.

@Entity(tableName = "folders", indices = [Index("parentId")])
internal data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("folderId"), Index("trashedAt"), Index("updatedAt")],
)
internal data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val folderId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val trashedAt: Long?,
    val scriptOverride: String?,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["documentId", "pageIndex"], unique = true), Index(value = ["fileKey"], unique = true)],
)
internal data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val fileKey: String,
    val sourceFile: String,           // file name inside the page directory, e.g. source.jpg
    val sourceW: Int,
    val sourceH: Int,
    val sourceBytes: Long,
    val origin: String,
    val rotation: Int,                // degrees: 0, 90, 180, 270
    val filter: String,
    val cropJson: String?,
    val brightness: Float,
    val contrast: Float,
    val editsVersion: Int,
    val renderVersion: Int?,          // editsVersion the render was made for; null = never rendered
    val renderFile: String?,          // file name inside the page directory (may equal sourceFile for identity edits)
    val renderW: Int,
    val renderH: Int,
    val renderBytes: Long,
    val thumbFile: String?,
    val textStatus: String,
    val textScript: String?,
)

@Entity(
    tableName = "page_text",
    foreignKeys = [
        ForeignKey(entity = PageEntity::class, parentColumns = ["id"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("documentId")],
)
internal data class PageTextEntity(
    @PrimaryKey val pageId: Long,
    val documentId: Long,
    val script: String,
    val text: String,
    val linesJson: String,
    val imageW: Int,
    val imageH: Int,
    val editsVersion: Int,
    val engine: String,
    val recognizedAt: Long,
)

/** External-content FTS5 index over page_text.text. Trigram = substring search in every script, CJK included. */
@Fts5(contentEntity = PageTextEntity::class, tokenizer = FtsOptions.TOKENIZER_TRIGRAM, contentRowId = "pageId")
@Entity(tableName = "page_text_fts")
internal data class PageTextFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val text: String,
)

internal data class DocumentWithPages(
    @Embedded val document: DocumentEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["documentId"]) val pages: List<PageEntity>,
)

/** One library row; aggregates come from the pages table. */
internal data class DocumentSummaryRow(
    val id: Long,
    val title: String,
    val folderId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val trashedAt: Long?,
    val pageCount: Int,
    val storedBytes: Long,
    val pendingPages: Int,
    val failedPages: Int,
    val skippedPages: Int,
    val donePages: Int,
    val coverFileKey: String?,
    val coverThumbFile: String?,
)

internal data class FolderRow(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val createdAt: Long,
    val documentCount: Int,
)

internal data class StatsRow(val documents: Int, val pages: Int, val storedBytes: Long)

internal data class TextHitRow(val pageId: Long, val documentId: Long, val pageIndex: Int, val text: String)

/** The files one page row refers to inside its directory. */
internal data class PageFileRow(
    val fileKey: String,
    val trashed: Boolean,
    val sourceFile: String,
    val renderFile: String?,
    val thumbFile: String?,
) {
    val names: Set<String> get() = setOfNotNull(sourceFile, renderFile, thumbFile)
}
