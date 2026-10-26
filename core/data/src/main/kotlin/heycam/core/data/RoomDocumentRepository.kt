package heycam.core.data

import androidx.room3.withWriteTransaction
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.db.DocumentEntity
import heycam.core.data.db.FolderEntity
import heycam.core.data.db.HeycamDatabase
import heycam.core.data.db.PageEntity
import heycam.core.data.db.PageTextEntity
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.LibrarySnapshot
import heycam.core.model.LibraryStats
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.SortOrder
import heycam.core.model.TextStatus
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
internal class RoomDocumentRepository @Inject constructor(
    private val db: HeycamDatabase,
    private val fileStore: FileStore,
    private val lock: StorageLock,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : DocumentRepository, PageWorkStore, PageTextEditor {

    private val folderDao = db.folderDao()
    private val docDao = db.documentDao()
    private val pageDao = db.pageDao()
    private val textDao = db.pageTextDao()
    private val map = Mappers(fileStore::pageDir)

    // ---- observation -------------------------------------------------------------------------------------------

    override fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot> =
        combine(folderDao.observeAll(), docDao.observeInFolder(folderId?.value)) { folderRows, docRows ->
            val byId = folderRows.associateBy { it.id }
            val current = folderId?.let { byId[it.value] }
            if (folderId != null && current == null) return@combine LibrarySnapshot(null, emptyList(), emptyList(), emptyList())
            // The take() guards against a parent cycle in a damaged database.
            val trail = generateSequence(current) { it.parentId?.let(byId::get) }.take(FolderRepository.MAX_DEPTH + 1).toList()
            LibrarySnapshot(
                folder = current?.let(Mappers::folder),
                breadcrumbs = trail.asReversed().map(Mappers::folder),
                folders = folderRows.filter { it.parentId == folderId?.value }.map(Mappers::folder).sortedWith(Mappers.byName { it.name }),
                documents = docRows.map(map::summary).sortedWith(Mappers.sortOrder(sort)),
            )
        }.distinctUntilChanged()

    override fun observeRecent(limit: Int): Flow<List<DocumentSummary>> =
        docDao.observeRecent(limit).map { rows -> rows.map(map::summary) }.distinctUntilChanged()

    override fun observeStats(): Flow<LibraryStats> =
        docDao.observeStats().map { LibraryStats(it.documents, it.pages, it.storedBytes) }.distinctUntilChanged()

    override fun observeDocument(id: DocumentId): Flow<Document?> =
        docDao.observeWithPages(id.value).map { it?.let(map::document) }.distinctUntilChanged()

    override fun observeText(id: DocumentId): Flow<List<PageText>> =
        textDao.observeForDocument(id.value).map { rows -> rows.map(Mappers::text) }.distinctUntilChanged()

    override fun observeTrash(): Flow<List<DocumentSummary>> =
        docDao.observeTrash().map { rows -> rows.map(map::summary) }.distinctUntilChanged()

    // ---- search ------------------------------------------------------------------------------------------------

    override suspend fun search(query: SearchQuery): List<SearchHit> = withContext(dispatchers.io) {
        val needle = SearchText.normalize(query.text)
        if (needle.isEmpty() || query.limit <= 0) return@withContext emptyList()
        val scope = query.folderId?.let { subtree(folderDao.all(), it.value) }
        val active = docDao.activeSummaries().filter { scope == null || it.folderId in scope }
        val titleHits = active.asSequence()
            .mapNotNull { row ->
                val at = row.title.indexOf(needle, ignoreCase = true)
                if (at < 0) null else SearchHit.TitleHit(map.summary(row), at until at + needle.length)
            }
            .take(query.limit)
            .toList()
        if (!query.inText) return@withContext titleHits
        val scopeIds = scope?.toList().orEmpty()
        val rows = if (SearchText.usesFts(needle)) {
            textDao.match(SearchText.ftsPhrase(needle), scope == null, scopeIds, query.limit)
        } else {
            textDao.contains(needle, scope == null, scopeIds, query.limit)
        }
        val summaries = active.associateBy { it.id }
        val textHits = rows.mapNotNull { row ->
            val summary = summaries[row.documentId] ?: return@mapNotNull null
            SearchHit.TextHit(map.summary(summary), PageId(row.pageId), row.pageIndex, Snippets.around(row.text, needle))
        }
        titleHits + textHits
    }

    private fun subtree(all: List<FolderEntity>, rootId: Long): Set<Long> {
        val children = all.groupBy { it.parentId }
        val result = mutableSetOf<Long>()
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (result.add(id)) children[id]?.forEach { queue.addLast(it.id) }
        }
        return result
    }

    // ---- documents ---------------------------------------------------------------------------------------------

    override suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId =
        withContext(dispatchers.io) {
            val cleanTitle = Names.clean(title, Names.MAX_TITLE)
            require(pages.isNotEmpty()) { "a document needs a page" }
            pages.forEach(::checkStaged)
            withFileMoves { moves ->
                db.withWriteTransaction {
                    val now = now()
                    val folder = folderId?.value?.takeIf { folderDao.get(it) != null }
                    val id = docDao.insert(
                        DocumentEntity(
                            title = cleanTitle,
                            folderId = folder,
                            createdAt = now,
                            updatedAt = now,
                            pinned = false,
                            trashedAt = null,
                            scriptOverride = null,
                        ),
                    )
                    pages.forEachIndexed { i, page -> pageDao.insert(newPage(id, i, page, moves)) }
                    DocumentId(id)
                }
            }
        }

    override suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int?): List<PageId> =
        withContext(dispatchers.io) {
            if (pages.isEmpty()) return@withContext emptyList()
            pages.forEach(::checkStaged)
            withFileMoves { moves ->
                db.withWriteTransaction {
                    val doc = docDao.get(documentId.value) ?: throw NoSuchElementException("document ${documentId.value}")
                    val existing = pageDao.pagesOf(doc.id)
                    val at = (atIndex ?: existing.size).coerceIn(0, existing.size)
                    pageDao.parkIndices(doc.id)
                    val ids = pages.mapIndexed { i, page -> pageDao.insert(newPage(doc.id, at + i, page, moves)) }
                    existing.forEachIndexed { i, page -> pageDao.setIndex(page.id, if (i < at) i else i + pages.size) }
                    docDao.touch(listOf(doc.id), now())
                    ids.map(::PageId)
                }
            }
        }

    override suspend fun replacePage(pageId: PageId, page: NewPage) = withContext(dispatchers.io) {
        checkStaged(page)
        withFileMoves { moves ->
            val oldKey = db.withWriteTransaction {
                val old = pageDao.get(pageId.value) ?: throw NoSuchElementException("page ${pageId.value}")
                val fresh = newPage(old.documentId, old.pageIndex, page, moves)
                textDao.delete(listOf(old.id))
                pageDao.update(fresh.copy(id = old.id, editsVersion = old.editsVersion + 1))
                docDao.touch(listOf(old.documentId), now())
                old.fileKey
            }
            FileOps.delete(fileStore.pageDir(oldKey))
        }
        Unit
    }

    override suspend fun updateEdits(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits) = withContext(dispatchers.io) {
        if (pageIds.isEmpty()) return@withContext
        db.withWriteTransaction {
            val changedDocs = mutableSetOf<Long>()
            for (page in pageDao.get(pageIds.map { it.value })) {
                val old = Mappers.edits(page)
                val new = transform(old)
                if (new == old) continue
                val status = enumOr(page.textStatus, TextStatus.PENDING)
                val nextStatus = if (!new.sameGeometryAs(old) && status != TextStatus.SKIPPED) TextStatus.PENDING else status
                pageDao.update(withEdits(page, new).copy(editsVersion = page.editsVersion + 1, textStatus = nextStatus.name))
                changedDocs += page.documentId
            }
            if (changedDocs.isNotEmpty()) docDao.touch(changedDocs.toList(), now())
        }
    }

    override suspend fun movePage(documentId: DocumentId, fromIndex: Int, toIndex: Int) = withContext(dispatchers.io) {
        db.withWriteTransaction {
            if (docDao.get(documentId.value) == null) throw NoSuchElementException("document ${documentId.value}")
            val order = pageDao.pagesOf(documentId.value).toMutableList()
            require(fromIndex in order.indices && toIndex in order.indices) { "page index out of range" }
            if (fromIndex != toIndex) {
                order.add(toIndex, order.removeAt(fromIndex))
                renumber(documentId.value, order)
                docDao.touch(listOf(documentId.value), now())
            }
        }
    }

    override suspend fun deletePages(pageIds: List<PageId>) = withContext(dispatchers.io) {
        if (pageIds.isEmpty()) return@withContext
        lock.withLock {
            val keys = db.withWriteTransaction {
                val doomed = pageDao.get(pageIds.map { it.value })
                if (doomed.isEmpty()) return@withWriteTransaction emptyList()
                textDao.delete(doomed.map { it.id })
                pageDao.delete(doomed.map { it.id })
                val docIds = doomed.map { it.documentId }.distinct()
                docIds.forEach { renumber(it, pageDao.pagesOf(it)) }
                docDao.touch(docIds, now())
                doomed.map { it.fileKey }
            }
            keys.forEach { FileOps.delete(fileStore.pageDir(it)) }
        }
    }

    override suspend fun rename(id: DocumentId, title: String) = withContext(dispatchers.io) {
        docDao.rename(id.value, Names.clean(title, Names.MAX_TITLE), now())
    }

    override suspend fun setPinned(id: DocumentId, pinned: Boolean) = withContext(dispatchers.io) {
        docDao.setPinned(id.value, pinned)
    }

    override suspend fun setScriptOverride(id: DocumentId, script: OcrScript?) = withContext(dispatchers.io) {
        docDao.setScriptOverride(id.value, script?.name)
    }

    override suspend fun moveToFolder(ids: List<DocumentId>, folderId: FolderId?) = withContext(dispatchers.io) {
        if (ids.isEmpty()) return@withContext
        db.withWriteTransaction {
            if (folderId != null && folderDao.get(folderId.value) == null) throw NoSuchElementException("folder ${folderId.value}")
            docDao.moveToFolder(ids.map { it.value }, folderId?.value)
        }
    }

    override suspend fun trash(ids: List<DocumentId>) = withContext(dispatchers.io) {
        if (ids.isNotEmpty()) docDao.trash(ids.map { it.value }, now())
    }

    override suspend fun restore(ids: List<DocumentId>) = withContext(dispatchers.io) {
        if (ids.isNotEmpty()) docDao.restore(ids.map { it.value })
    }

    override suspend fun deleteForever(ids: List<DocumentId>) = withContext(dispatchers.io) {
        if (ids.isEmpty()) return@withContext
        lock.withLock { deleteDocumentsLocked(ids.map { it.value }) }
    }

    override suspend fun purgeTrash(trashedBefore: Instant): Int = withContext(dispatchers.io) {
        lock.withLock {
            val ids = docDao.trashedBefore(trashedBefore.toEpochMilli())
            if (ids.isNotEmpty()) deleteDocumentsLocked(ids)
            ids.size
        }
    }

    private suspend fun deleteDocumentsLocked(ids: List<Long>) {
        val keys = db.withWriteTransaction {
            val keys = pageDao.pagesOf(ids).map { it.fileKey }
            textDao.deleteForDocuments(ids)
            docDao.delete(ids)
            keys
        }
        keys.forEach { FileOps.delete(fileStore.pageDir(it)) }
    }

    override suspend fun updateText(pageId: PageId, text: String) = withContext(dispatchers.io) {
        db.withWriteTransaction {
            val page = pageDao.get(pageId.value) ?: throw NoSuchElementException("page ${pageId.value}")
            val clean = text.nfc()
            val now = now()
            if (textDao.get(page.id) != null) {
                textDao.setText(page.id, clean)
            } else {
                val rendered = page.renderVersion == page.editsVersion && page.renderFile != null
                textDao.upsert(
                    PageTextEntity(
                        pageId = page.id,
                        documentId = page.documentId,
                        script = page.textScript ?: OcrScript.LATIN.name,
                        text = clean,
                        linesJson = Codecs.encodeLines(emptyList()),
                        imageW = if (rendered) page.renderW else page.sourceW,
                        imageH = if (rendered) page.renderH else page.sourceH,
                        editsVersion = page.editsVersion,
                        engine = EDITED_ENGINE,
                        recognizedAt = now,
                    ),
                )
            }
            pageDao.setTextStatus(page.id, (if (clean.isBlank()) TextStatus.EMPTY else TextStatus.DONE).name)
            docDao.touch(listOf(page.documentId), now)
        }
    }

    // ---- PageWorkStore -----------------------------------------------------------------------------------------

    override suspend fun pagesNeedingRender(limit: Int): List<Page> = withContext(dispatchers.io) {
        pageDao.needingRender(limit).map(map::page)
    }

    override suspend fun pagesNeedingText(limit: Int): List<Page> = withContext(dispatchers.io) {
        pageDao.needingText(limit).map(map::page)
    }

    override suspend fun saveRender(pageId: PageId, editsVersion: Int, render: ImageInfo, thumbPath: String): Boolean =
        withContext(dispatchers.io) {
            lock.withLock {
                val superseded = db.withWriteTransaction {
                    val page = pageDao.get(pageId.value)
                    if (page == null || page.editsVersion != editsVersion) return@withWriteTransaction null
                    val dir = fileStore.pageDir(page.fileKey)
                    val renderName = nameInside(dir, render.path)
                    val thumbName = nameInside(dir, thumbPath)
                    pageDao.update(
                        page.copy(
                            renderVersion = editsVersion,
                            renderFile = renderName,
                            renderW = render.widthPx,
                            renderH = render.heightPx,
                            renderBytes = render.bytes,
                            thumbFile = thumbName,
                        ),
                    )
                    listOfNotNull(page.renderFile, page.thumbFile)
                        .filter { it != renderName && it != thumbName && it != page.sourceFile }
                        .map { File(dir, it) }
                }
                superseded?.forEach { FileOps.delete(it) }
                superseded != null
            }
        }

    override suspend fun saveText(text: PageText): Boolean = withContext(dispatchers.io) {
        db.withWriteTransaction {
            val page = pageDao.get(text.pageId.value)
            if (page == null || page.editsVersion != text.editsVersion) return@withWriteTransaction false
            val clean = text.text.nfc()
            textDao.upsert(
                PageTextEntity(
                    pageId = page.id,
                    documentId = page.documentId,
                    script = text.script.name,
                    text = clean,
                    linesJson = Codecs.encodeLines(text.lines),
                    imageW = text.imageWidthPx,
                    imageH = text.imageHeightPx,
                    editsVersion = text.editsVersion,
                    engine = text.engine,
                    recognizedAt = text.recognizedAt.toEpochMilli(),
                ),
            )
            val status = if (clean.isBlank()) TextStatus.EMPTY else TextStatus.DONE
            pageDao.update(page.copy(textStatus = status.name, textScript = text.script.name))
            true
        }
    }

    override suspend fun setTextStatus(pageId: PageId, editsVersion: Int, status: TextStatus): Boolean = withContext(dispatchers.io) {
        db.withWriteTransaction {
            val page = pageDao.get(pageId.value)
            if (page == null || page.editsVersion != editsVersion) return@withWriteTransaction false
            pageDao.setTextStatus(page.id, status.name)
            true
        }
    }

    override suspend fun markTextPending(documentId: DocumentId) = withContext(dispatchers.io) {
        pageDao.markTextPending(documentId.value)
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun now() = clock.now().toEpochMilli()

    private fun checkStaged(page: NewPage) {
        require(page.image.file.isFile) { "staged image is missing" }
        require(page.image.widthPx > 0 && page.image.heightPx > 0) { "staged image has no size" }
    }

    /** Moves the staged file into a fresh page directory and builds its row. */
    private fun newPage(documentId: Long, index: Int, page: NewPage, moves: FileMoves): PageEntity {
        val key = UUID.randomUUID().toString()
        val name = PageFiles.sourceNameFor(page.image.mime)
        val target = File(fileStore.pageDir(key), name)
        moves.move(page.image.file, target)
        val edits = page.edits
        return PageEntity(
            documentId = documentId,
            pageIndex = index,
            fileKey = key,
            sourceFile = name,
            sourceW = page.image.widthPx,
            sourceH = page.image.heightPx,
            sourceBytes = target.length(),
            origin = page.image.origin.name,
            rotation = edits.rotation.degrees,
            filter = edits.filter.name,
            cropJson = Codecs.encodeCrop(edits.crop),
            brightness = edits.adjust.brightness,
            contrast = edits.adjust.contrast,
            editsVersion = 0,
            renderVersion = null,
            renderFile = null,
            renderW = 0,
            renderH = 0,
            renderBytes = 0,
            thumbFile = null,
            textStatus = TextStatus.PENDING.name,
            textScript = null,
        )
    }

    private fun withEdits(page: PageEntity, edits: PageEdits) = page.copy(
        rotation = edits.rotation.degrees,
        filter = edits.filter.name,
        cropJson = Codecs.encodeCrop(edits.crop),
        brightness = edits.adjust.brightness,
        contrast = edits.adjust.contrast,
    )

    private suspend fun renumber(documentId: Long, ordered: List<PageEntity>) {
        pageDao.parkIndices(documentId)
        ordered.forEachIndexed { i, page -> pageDao.setIndex(page.id, i) }
    }

    private fun nameInside(dir: File, path: String): String {
        val file = File(path)
        require(file.parentFile?.canonicalFile == dir.canonicalFile) { "render files must live in the page directory" }
        return file.name
    }

    /**
     * Runs [block] under the storage lock; on failure every staged file it moved goes back where it was.
     *
     * Waiting for the lock can be cancelled, but once the lock is held the block runs to the end: Room can report a
     * cancellation after its transaction has committed, and moving the files back then would leave saved pages
     * without their files. So rows and files are saved or rolled back together, and a cancelled caller can still
     * get a CancellationException after a complete save.
     */
    private suspend fun <T> withFileMoves(block: suspend (FileMoves) -> T): T = lock.withLock {
        withContext(NonCancellable) {
            val moves = FileMoves()
            try {
                block(moves)
            } catch (t: Throwable) {
                moves.rollback()
                throw t
            }
        }
    }

    private inner class FileMoves {
        private val done = mutableListOf<Pair<File, File>>()

        fun move(source: File, target: File) {
            FileOps.move(source, target)
            done += source to target
        }

        fun rollback() {
            done.asReversed().forEach { (source, target) ->
                runCatching { FileOps.move(target, source) }.onFailure { log.w(TAG, "could not restore a staged file", it) }
                target.parentFile?.let(FileOps::delete)
            }
        }
    }

    private companion object {
        const val TAG = "documents"
        const val EDITED_ENGINE = "edited"
    }
}
