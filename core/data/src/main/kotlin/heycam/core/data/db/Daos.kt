package heycam.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Rendered bytes count only when the render is its own file (identity edits reuse the source). */
private const val STORED_BYTES =
    "p.sourceBytes + CASE WHEN p.renderVersion IS NOT NULL AND p.renderFile IS NOT NULL AND p.renderFile <> p.sourceFile " +
        "THEN p.renderBytes ELSE 0 END"

private const val SUMMARY_SELECT =
    "SELECT d.id, d.title, d.folderId, d.createdAt, d.updatedAt, d.pinned, d.trashedAt, " +
        "COUNT(p.id) AS pageCount, " +
        "COALESCE(SUM($STORED_BYTES), 0) AS storedBytes, " +
        "COALESCE(SUM(p.textStatus = 'PENDING'), 0) AS pendingPages, " +
        "COALESCE(SUM(p.textStatus = 'FAILED'), 0) AS failedPages, " +
        "COALESCE(SUM(p.textStatus = 'SKIPPED'), 0) AS skippedPages, " +
        "COALESCE(SUM(p.textStatus = 'DONE'), 0) AS donePages, " +
        "(SELECT c.fileKey FROM pages c WHERE c.documentId = d.id AND c.pageIndex = 0) AS coverFileKey, " +
        "(SELECT c.thumbFile FROM pages c WHERE c.documentId = d.id AND c.pageIndex = 0) AS coverThumbFile " +
        "FROM documents d LEFT JOIN pages p ON p.documentId = d.id "

@Dao
internal interface FolderDao {
    @Query(
        "SELECT f.id, f.name, f.parentId, f.createdAt, " +
            "(SELECT COUNT(*) FROM documents d WHERE d.folderId = f.id AND d.trashedAt IS NULL) AS documentCount " +
            "FROM folders f",
    )
    fun observeAll(): Flow<List<FolderRow>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: Long): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("UPDATE folders SET parentId = :newParentId WHERE parentId = :parentId")
    suspend fun reparentChildren(parentId: Long, newParentId: Long?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
internal interface DocumentDao {
    @Query("$SUMMARY_SELECT WHERE d.trashedAt IS NULL AND d.folderId IS :folderId GROUP BY d.id")
    fun observeInFolder(folderId: Long?): Flow<List<DocumentSummaryRow>>

    @Query("$SUMMARY_SELECT WHERE d.trashedAt IS NULL GROUP BY d.id ORDER BY d.updatedAt DESC, d.id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DocumentSummaryRow>>

    @Query("$SUMMARY_SELECT WHERE d.trashedAt IS NOT NULL GROUP BY d.id ORDER BY d.trashedAt DESC, d.id DESC")
    fun observeTrash(): Flow<List<DocumentSummaryRow>>

    @Query("$SUMMARY_SELECT WHERE d.trashedAt IS NULL GROUP BY d.id ORDER BY d.updatedAt DESC, d.id DESC")
    suspend fun activeSummaries(): List<DocumentSummaryRow>

    @Query("$SUMMARY_SELECT WHERE d.id IN (:ids) GROUP BY d.id")
    suspend fun summaries(ids: List<Long>): List<DocumentSummaryRow>

    @Query(
        "SELECT (SELECT COUNT(*) FROM documents WHERE trashedAt IS NULL) AS documents, " +
            "(SELECT COUNT(*) FROM pages p JOIN documents d ON d.id = p.documentId WHERE d.trashedAt IS NULL) AS pages, " +
            "(SELECT COALESCE(SUM($STORED_BYTES), 0) FROM pages p JOIN documents d ON d.id = p.documentId " +
            "WHERE d.trashedAt IS NULL) AS storedBytes",
    )
    fun observeStats(): Flow<StatsRow>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeWithPages(id: Long): Flow<DocumentWithPages?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): DocumentEntity?

    @Query("SELECT id FROM documents WHERE id IN (:ids)")
    suspend fun existing(ids: List<Long>): List<Long>

    @Query("SELECT id FROM documents WHERE trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun trashedBefore(before: Long): List<Long>

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Query("UPDATE documents SET updatedAt = :at WHERE id IN (:ids)")
    suspend fun touch(ids: List<Long>, at: Long)

    @Query("UPDATE documents SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, title: String, at: Long)

    @Query("UPDATE documents SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE documents SET scriptOverride = :script WHERE id = :id")
    suspend fun setScriptOverride(id: Long, script: String?)

    @Query("UPDATE documents SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun moveToFolder(ids: List<Long>, folderId: Long?)

    @Query("UPDATE documents SET folderId = :target WHERE folderId = :folderId")
    suspend fun moveAllFromFolder(folderId: Long, target: Long?)

    @Query("UPDATE documents SET trashedAt = :at WHERE id IN (:ids) AND trashedAt IS NULL")
    suspend fun trash(ids: List<Long>, at: Long)

    @Query("UPDATE documents SET trashedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>)

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
internal interface PageDao {
    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: Long): PageEntity?

    @Query("SELECT * FROM pages WHERE id IN (:ids)")
    suspend fun get(ids: List<Long>): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex")
    suspend fun pagesOf(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId IN (:documentIds)")
    suspend fun pagesOf(documentIds: List<Long>): List<PageEntity>

    @Query(
        "SELECT p.* FROM pages p JOIN documents d ON d.id = p.documentId " +
            "WHERE d.trashedAt IS NULL AND (p.renderVersion IS NULL OR p.renderVersion <> p.editsVersion) " +
            "ORDER BY d.updatedAt DESC, d.id DESC, p.pageIndex LIMIT :limit",
    )
    suspend fun needingRender(limit: Int): List<PageEntity>

    @Query(
        "SELECT p.* FROM pages p JOIN documents d ON d.id = p.documentId " +
            "WHERE d.trashedAt IS NULL AND p.textStatus = 'PENDING' AND p.renderVersion = p.editsVersion " +
            "ORDER BY d.updatedAt DESC, d.id DESC, p.pageIndex LIMIT :limit",
    )
    suspend fun needingText(limit: Int): List<PageEntity>

    @Query(
        "SELECT p.fileKey AS fileKey, (d.trashedAt IS NOT NULL) AS trashed, p.sourceFile AS sourceFile, " +
            "p.renderFile AS renderFile, p.thumbFile AS thumbFile FROM pages p JOIN documents d ON d.id = p.documentId",
    )
    suspend fun files(): List<PageFileRow>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Update
    suspend fun update(page: PageEntity)

    /** Moves every page of the document to a negative index so a renumbering never trips the unique index. */
    @Query("UPDATE pages SET pageIndex = -1 - pageIndex WHERE documentId = :documentId")
    suspend fun parkIndices(documentId: Long)

    @Query("UPDATE pages SET pageIndex = :index WHERE id = :id")
    suspend fun setIndex(id: Long, index: Int)

    @Query("UPDATE pages SET textStatus = :status WHERE id = :id")
    suspend fun setTextStatus(id: Long, status: String)

    @Query("UPDATE pages SET textStatus = 'PENDING' WHERE documentId = :documentId")
    suspend fun markTextPending(documentId: Long)

    @Query("DELETE FROM pages WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
internal interface PageTextDao {
    @Query(
        "SELECT t.* FROM page_text t JOIN pages p ON p.id = t.pageId WHERE p.documentId = :documentId ORDER BY p.pageIndex",
    )
    fun observeForDocument(documentId: Long): Flow<List<PageTextEntity>>

    @Query("SELECT * FROM page_text WHERE pageId = :pageId")
    suspend fun get(pageId: Long): PageTextEntity?

    @Upsert
    suspend fun upsert(text: PageTextEntity)

    @Query("UPDATE page_text SET text = :text WHERE pageId = :pageId")
    suspend fun setText(pageId: Long, text: String)

    @Query("DELETE FROM page_text WHERE pageId IN (:pageIds)")
    suspend fun delete(pageIds: List<Long>)

    @Query("DELETE FROM page_text WHERE pageId IN (SELECT id FROM pages WHERE documentId IN (:documentIds))")
    suspend fun deleteForDocuments(documentIds: List<Long>)

    @Query(
        "SELECT t.pageId AS pageId, t.documentId AS documentId, p.pageIndex AS pageIndex, t.text AS text " +
            "FROM page_text_fts f JOIN page_text t ON t.pageId = f.rowid " +
            "JOIN pages p ON p.id = t.pageId JOIN documents d ON d.id = p.documentId " +
            "WHERE page_text_fts MATCH :match AND d.trashedAt IS NULL AND (:anyFolder OR d.folderId IN (:folderIds)) " +
            "ORDER BY d.updatedAt DESC, d.id DESC, p.pageIndex LIMIT :limit",
    )
    suspend fun match(match: String, anyFolder: Boolean, folderIds: List<Long>, limit: Int): List<TextHitRow>

    @Query(
        "SELECT t.pageId AS pageId, t.documentId AS documentId, p.pageIndex AS pageIndex, t.text AS text " +
            "FROM page_text t JOIN pages p ON p.id = t.pageId JOIN documents d ON d.id = p.documentId " +
            "WHERE instr(lower(t.text), lower(:needle)) > 0 AND d.trashedAt IS NULL " +
            "AND (:anyFolder OR d.folderId IN (:folderIds)) " +
            "ORDER BY d.updatedAt DESC, d.id DESC, p.pageIndex LIMIT :limit",
    )
    suspend fun contains(needle: String, anyFolder: Boolean, folderIds: List<Long>, limit: Int): List<TextHitRow>
}
