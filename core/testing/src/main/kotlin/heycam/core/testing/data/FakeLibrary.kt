package heycam.core.testing.data

import heycam.core.common.Clock
import heycam.core.data.DocumentRepository
import heycam.core.data.FolderRepository
import heycam.core.data.FolderNameInUseException
import heycam.core.data.FolderTooDeepException
import heycam.core.data.NewPage
import heycam.core.data.PageTextEditor
import heycam.core.data.PageWorkStore
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
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
import heycam.core.model.Snippet
import heycam.core.model.SortOrder
import heycam.core.model.TextState
import heycam.core.model.TextStatus
import heycam.core.testing.common.TestClock
import java.text.Normalizer
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory library behind [FakeDocumentRepository], [FakeFolderRepository] and [FakePageWorkStore], with the
 * same rules as the Room implementation where screens can tell the difference: dense page indices, editsVersion
 * bumps, stale-version rejection, trash hidden everywhere but the trash, folder depth, blank-name errors.
 *
 * Simplifications: no files are touched (a page's source is the staged file's path), search is a case-insensitive
 * substring scan, and every mutation is instant. Set [failWith] to make the next calls throw (error states).
 */
class FakeLibrary(private val clock: Clock = TestClock()) : DocumentRepository, PageWorkStore, PageTextEditor, FolderRepository {

    data class DocRow(
        val id: Long,
        val title: String,
        val folderId: Long?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val pinned: Boolean = false,
        val trashedAt: Instant? = null,
        val scriptOverride: OcrScript? = null,
    )

    data class FolderRow(val id: Long, val name: String, val parentId: Long?, val createdAt: Instant)

    data class State(
        val folders: Map<Long, FolderRow> = emptyMap(),
        val documents: Map<Long, DocRow> = emptyMap(),
        val pages: Map<Long, Page> = emptyMap(),
        val texts: Map<Long, PageText> = emptyMap(),
        val nextId: Long = 1,
    )

    val state = MutableStateFlow(State())

    /** When set, every suspending call throws it. */
    @Volatile var failWith: Throwable? = null

    // ---- seeding -------------------------------------------------------------------------------------------------

    /** Adds a document with [pageCount] rendered pages and, when given, their recognised text. */
    fun seed(
        title: String,
        pageCount: Int = 1,
        folderId: FolderId? = null,
        texts: List<String> = emptyList(),
        pinned: Boolean = false,
        at: Instant = clock.now(),
    ): DocumentId {
        var id = 0L
        state.update { s ->
            id = s.nextId
            var next = s.nextId + 1
            val pages = (0 until pageCount).associate { index ->
                val pageId = next++
                val source = ImageInfo("/fake/pages/$pageId/source.jpg", 1240, 1754, 100_000, JPEG)
                pageId to Page(
                    id = PageId(pageId), documentId = DocumentId(id), index = index, fileKey = "fake-$pageId",
                    source = source, edits = PageEdits(), editsVersion = 0, render = source,
                    thumbPath = "/fake/pages/$pageId/thumb.jpg", origin = PageOrigin.SCANNER,
                    textStatus = if (index < texts.size) TextStatus.DONE else TextStatus.PENDING, textScript = null,
                )
            }
            val pageTexts = pages.values.filter { it.index < texts.size }.associate { page ->
                page.id.value to PageText(page.id, OcrScript.LATIN, nfc(texts[page.index]), emptyList(), 1240, 1754, 0, "fake", at)
            }
            s.copy(
                documents = s.documents + (id to DocRow(id, title, folderId?.value, at, at, pinned = pinned)),
                pages = s.pages + pages,
                texts = s.texts + pageTexts,
                nextId = next,
            )
        }
        return DocumentId(id)
    }

    // ---- DocumentRepository ------------------------------------------------------------------------------------

    override fun observeLibrary(folderId: FolderId?, sort: SortOrder): Flow<LibrarySnapshot> = state.map { s ->
        val current = folderId?.let { s.folders[it.value] }
        if (folderId != null && current == null) return@map LibrarySnapshot(null, emptyList(), emptyList(), emptyList())
        val trail = generateSequence(current) { f -> f.parentId?.let { s.folders[it] } }.take(FolderRepository.MAX_DEPTH + 1).toList()
        LibrarySnapshot(
            folder = current?.let { folder(s, it) },
            breadcrumbs = trail.asReversed().map { folder(s, it) },
            folders = s.folders.values.filter { it.parentId == folderId?.value }.map { folder(s, it) }.sortedBy { it.name.lowercase() },
            documents = s.documents.values.filter { it.trashedAt == null && it.folderId == folderId?.value }.map { summary(s, it) }.sortedWith(order(sort)),
        )
    }.distinctUntilChanged()

    override fun observeRecent(limit: Int): Flow<List<DocumentSummary>> = state.map { s ->
        s.documents.values.filter { it.trashedAt == null }.map { summary(s, it) }.sortedWith(order(SortOrder.UPDATED_DESC)).take(limit)
    }.distinctUntilChanged()

    override fun observeStats(): Flow<LibraryStats> = state.map { s ->
        val live = s.documents.values.filter { it.trashedAt == null }.map { summary(s, it) }
        LibraryStats(live.size, live.sumOf { it.pageCount }, live.sumOf { it.storedBytes })
    }.distinctUntilChanged()

    override fun observeDocument(id: DocumentId): Flow<Document?> = state.map { s -> s.documents[id.value]?.let { document(s, it) } }.distinctUntilChanged()

    override fun observeText(id: DocumentId): Flow<List<PageText>> = state.map { s ->
        pagesOf(s, id.value).mapNotNull { s.texts[it.id.value] }
    }.distinctUntilChanged()

    override fun observeTrash(): Flow<List<DocumentSummary>> = state.map { s ->
        s.documents.values.filter { it.trashedAt != null }.sortedWith(compareByDescending<DocRow> { it.trashedAt }.thenByDescending { it.id }).map { summary(s, it) }
    }.distinctUntilChanged()

    override suspend fun search(query: SearchQuery): List<SearchHit> {
        check()
        val s = state.value
        val needle = nfc(query.text).trim()
        if (needle.isEmpty() || query.limit <= 0) return emptyList()
        val scope = query.folderId?.let { subtree(s, it.value) }
        val live = s.documents.values.filter { it.trashedAt == null && (scope == null || it.folderId in scope) }
            .sortedWith(compareByDescending<DocRow> { it.updatedAt }.thenByDescending { it.id })
        val titles = live.mapNotNull { d ->
            val at = d.title.indexOf(needle, ignoreCase = true)
            if (at < 0) null else SearchHit.TitleHit(summary(s, d), at until at + needle.length)
        }.take(query.limit)
        if (!query.inText) return titles
        val texts = live.flatMap { d -> pagesOf(s, d.id).map { d to it } }.mapNotNull { (d, page) ->
            val text = s.texts[page.id.value]?.text ?: return@mapNotNull null
            val at = text.indexOf(needle, ignoreCase = true)
            if (at < 0) return@mapNotNull null
            val start = (at - SNIPPET_RADIUS).coerceAtLeast(0)
            val end = (at + needle.length + SNIPPET_RADIUS).coerceAtMost(text.length)
            val window = text.substring(start, end).replace('\n', ' ')
            SearchHit.TextHit(summary(s, d), page.id, page.index, Snippet(window, listOf((at - start) until (at - start + needle.length))))
        }.take(query.limit)
        return titles + texts
    }

    override suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId {
        check()
        val clean = cleanName(title)
        require(pages.isNotEmpty()) { "a document needs a page" }
        var id = 0L
        state.update { s ->
            id = s.nextId
            val now = clock.now()
            val folder = folderId?.value?.takeIf { it in s.folders }
            val (rows, next) = newPages(id, 0, pages, s.nextId + 1)
            s.copy(documents = s.documents + (id to DocRow(id, clean, folder, now, now)), pages = s.pages + rows, nextId = next)
        }
        return DocumentId(id)
    }

    override suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int?): List<PageId> {
        check()
        if (pages.isEmpty()) return emptyList()
        var ids = emptyList<PageId>()
        state.update { s ->
            val doc = s.documents[documentId.value] ?: throw NoSuchElementException("document ${documentId.value}")
            val existing = pagesOf(s, doc.id)
            val at = (atIndex ?: existing.size).coerceIn(0, existing.size)
            val (rows, next) = newPages(doc.id, at, pages, s.nextId)
            ids = rows.keys.map(::PageId)
            val shifted = existing.associate { p -> p.id.value to p.copy(index = if (p.index < at) p.index else p.index + pages.size) }
            s.copy(pages = s.pages + shifted + rows, documents = touch(s, doc.id), nextId = next)
        }
        return ids
    }

    override suspend fun replacePage(pageId: PageId, page: NewPage) {
        check()
        state.update { s ->
            val old = s.pages[pageId.value] ?: throw NoSuchElementException("page ${pageId.value}")
            val (rows, next) = newPages(old.documentId.value, old.index, listOf(page), s.nextId)
            val fresh = rows.values.single().copy(id = old.id, editsVersion = old.editsVersion + 1)
            s.copy(pages = s.pages + (old.id.value to fresh), texts = s.texts - old.id.value, documents = touch(s, old.documentId.value), nextId = next)
        }
    }

    override suspend fun updateEdits(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits) {
        check()
        state.update { s ->
            var docs = s.documents
            val changed = pageIds.mapNotNull { s.pages[it.value] }.mapNotNull { page ->
                val edits = transform(page.edits)
                if (edits == page.edits) return@mapNotNull null
                docs = touch(s.copy(documents = docs), page.documentId.value)
                val status = if (!edits.sameGeometryAs(page.edits) && page.textStatus != TextStatus.SKIPPED) TextStatus.PENDING else page.textStatus
                page.id.value to page.copy(edits = edits, editsVersion = page.editsVersion + 1, render = null, textStatus = status)
            }
            s.copy(pages = s.pages + changed, documents = docs)
        }
    }

    override suspend fun movePage(documentId: DocumentId, fromIndex: Int, toIndex: Int) {
        check()
        state.update { s ->
            if (documentId.value !in s.documents) throw NoSuchElementException("document ${documentId.value}")
            val order = pagesOf(s, documentId.value).toMutableList()
            require(fromIndex in order.indices && toIndex in order.indices) { "page index out of range" }
            if (fromIndex == toIndex) return@update s
            order.add(toIndex, order.removeAt(fromIndex))
            s.copy(pages = s.pages + renumbered(order), documents = touch(s, documentId.value))
        }
    }

    override suspend fun deletePages(pageIds: List<PageId>) {
        check()
        state.update { s ->
            val doomed = pageIds.mapNotNull { s.pages[it.value] }
            if (doomed.isEmpty()) return@update s
            val remaining = s.copy(pages = s.pages - doomed.map { it.id.value }.toSet(), texts = s.texts - doomed.map { it.id.value }.toSet())
            var docs = remaining.documents
            var pages = remaining.pages
            for (docId in doomed.map { it.documentId.value }.distinct()) {
                pages = pages + renumbered(pagesOf(remaining, docId))
                docs = touch(remaining.copy(documents = docs), docId)
            }
            remaining.copy(pages = pages, documents = docs)
        }
    }

    override suspend fun rename(id: DocumentId, title: String) {
        check()
        val clean = cleanName(title)
        editDocument(id) { it.copy(title = clean, updatedAt = clock.now()) }
    }

    override suspend fun setPinned(id: DocumentId, pinned: Boolean) {
        check()
        editDocument(id) { it.copy(pinned = pinned) }
    }

    override suspend fun setScriptOverride(id: DocumentId, script: OcrScript?) {
        check()
        editDocument(id) { it.copy(scriptOverride = script) }
    }

    override suspend fun moveToFolder(ids: List<DocumentId>, folderId: FolderId?) {
        check()
        if (folderId != null && folderId.value !in state.value.folders) throw NoSuchElementException("folder ${folderId.value}")
        ids.forEach { id -> editDocument(id) { it.copy(folderId = folderId?.value) } }
    }

    override suspend fun trash(ids: List<DocumentId>) {
        check()
        val now = clock.now()
        ids.forEach { id -> editDocument(id) { if (it.trashedAt == null) it.copy(trashedAt = now) else it } }
    }

    override suspend fun restore(ids: List<DocumentId>) {
        check()
        ids.forEach { id -> editDocument(id) { it.copy(trashedAt = null) } }
    }

    override suspend fun deleteForever(ids: List<DocumentId>) {
        check()
        removeDocuments(ids.map { it.value }.toSet())
    }

    override suspend fun purgeTrash(trashedBefore: Instant): Int {
        check()
        val doomed = state.value.documents.values.filter { it.trashedAt?.isBefore(trashedBefore) == true }.map { it.id }.toSet()
        removeDocuments(doomed)
        return doomed.size
    }

    // ---- PageTextEditor ----------------------------------------------------------------------------------------

    override suspend fun updateText(pageId: PageId, text: String) {
        check()
        state.update { s ->
            val page = s.pages[pageId.value] ?: throw NoSuchElementException("page ${pageId.value}")
            val clean = nfc(text)
            val image = page.render ?: page.source
            val stored = s.texts[page.id.value]?.copy(text = clean)
                ?: PageText(page.id, page.textScript ?: OcrScript.LATIN, clean, emptyList(), image.widthPx, image.heightPx, page.editsVersion, "edited", clock.now())
            val status = if (clean.isBlank()) TextStatus.EMPTY else TextStatus.DONE
            s.copy(texts = s.texts + (page.id.value to stored), pages = s.pages + (page.id.value to page.copy(textStatus = status)), documents = touch(s, page.documentId.value))
        }
    }

    // ---- PageWorkStore -----------------------------------------------------------------------------------------

    override suspend fun pagesNeedingRender(limit: Int): List<Page> {
        check()
        return livePages().filter { it.render == null }.take(limit)
    }

    override suspend fun pagesNeedingText(limit: Int): List<Page> {
        check()
        return livePages().filter { it.render != null && it.textStatus == TextStatus.PENDING }.take(limit)
    }

    override suspend fun saveRender(pageId: PageId, editsVersion: Int, render: ImageInfo, thumbPath: String): Boolean =
        writePage(pageId, editsVersion) { it.copy(render = render, thumbPath = thumbPath) }

    override suspend fun saveText(text: PageText): Boolean {
        check()
        var saved = false
        state.update { s ->
            val page = s.pages[text.pageId.value]
            if (page == null || page.editsVersion != text.editsVersion) return@update s.also { saved = false }
            saved = true
            val clean = text.copy(text = nfc(text.text))
            val status = if (clean.text.isBlank()) TextStatus.EMPTY else TextStatus.DONE
            s.copy(texts = s.texts + (page.id.value to clean), pages = s.pages + (page.id.value to page.copy(textStatus = status, textScript = text.script)))
        }
        return saved
    }

    override suspend fun setTextStatus(pageId: PageId, editsVersion: Int, status: TextStatus): Boolean =
        writePage(pageId, editsVersion) { it.copy(textStatus = status) }

    override suspend fun markTextPending(documentId: DocumentId) {
        check()
        state.update { s -> s.copy(pages = s.pages + pagesOf(s, documentId.value).associate { it.id.value to it.copy(textStatus = TextStatus.PENDING) }) }
    }

    // ---- FolderRepository --------------------------------------------------------------------------------------

    override fun observeFolders(parentId: FolderId?): Flow<List<Folder>> = state.map { s ->
        s.folders.values.filter { it.parentId == parentId?.value }.map { folder(s, it) }.sortedBy { it.name.lowercase() }
    }.distinctUntilChanged()

    override suspend fun create(name: String, parentId: FolderId?): FolderId {
        check()
        val clean = cleanName(name)
        var id = 0L
        state.update { s ->
            if (parentId != null) {
                if (parentId.value !in s.folders) throw NoSuchElementException("folder ${parentId.value}")
                val depth = generateSequence(s.folders[parentId.value]) { f -> f.parentId?.let { s.folders[it] } }.count()
                if (depth >= FolderRepository.MAX_DEPTH) throw FolderTooDeepException()
            }
            requireFreeName(s, parentId?.value, clean, except = null)
            id = s.nextId
            s.copy(folders = s.folders + (id to FolderRow(id, clean, parentId?.value, clock.now())), nextId = s.nextId + 1)
        }
        return FolderId(id)
    }

    override suspend fun rename(id: FolderId, name: String) {
        check()
        val clean = cleanName(name)
        state.update { s ->
            s.folders[id.value]?.let {
                requireFreeName(s, it.parentId, clean, except = it.id)
                s.copy(folders = s.folders + (id.value to it.copy(name = clean)))
            } ?: s
        }
    }

    override suspend fun delete(id: FolderId, moveDocumentsTo: FolderId?) {
        check()
        state.update { s ->
            val folder = s.folders[id.value] ?: return@update s
            val target = moveDocumentsTo?.value?.takeIf { it != folder.id && it in s.folders }
            s.copy(
                folders = (s.folders - folder.id).mapValues { (_, f) -> if (f.parentId == folder.id) f.copy(parentId = folder.parentId) else f },
                documents = s.documents.mapValues { (_, d) -> if (d.folderId == folder.id) d.copy(folderId = target) else d },
            )
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun check() {
        failWith?.let { throw it }
    }

    private fun livePages(): List<Page> {
        val s = state.value
        return s.documents.values.filter { it.trashedAt == null }.sortedByDescending { it.updatedAt }.flatMap { pagesOf(s, it.id) }
    }

    private fun writePage(pageId: PageId, editsVersion: Int, change: (Page) -> Page): Boolean {
        check()
        var written = false
        state.update { s ->
            val page = s.pages[pageId.value]
            written = page != null && page.editsVersion == editsVersion
            if (!written) s else s.copy(pages = s.pages + (pageId.value to change(page!!)))
        }
        return written
    }

    private fun editDocument(id: DocumentId, change: (DocRow) -> DocRow) =
        state.update { s -> s.documents[id.value]?.let { s.copy(documents = s.documents + (id.value to change(it))) } ?: s }

    private fun removeDocuments(ids: Set<Long>) = state.update { s ->
        val pageIds = s.pages.values.filter { it.documentId.value in ids }.map { it.id.value }.toSet()
        s.copy(documents = s.documents - ids, pages = s.pages - pageIds, texts = s.texts - pageIds)
    }

    private fun touch(s: State, documentId: Long): Map<Long, DocRow> =
        s.documents[documentId]?.let { s.documents + (documentId to it.copy(updatedAt = clock.now())) } ?: s.documents

    private fun newPages(documentId: Long, firstIndex: Int, pages: List<NewPage>, firstId: Long): Pair<Map<Long, Page>, Long> {
        var next = firstId
        val rows = pages.mapIndexed { i, staged ->
            val id = next++
            val image = staged.image
            id to Page(
                id = PageId(id), documentId = DocumentId(documentId), index = firstIndex + i, fileKey = "fake-$id",
                source = ImageInfo(image.file.absolutePath, image.widthPx, image.heightPx, image.file.length(), image.mime),
                edits = staged.edits, editsVersion = 0, render = null, thumbPath = null, origin = image.origin,
                textStatus = TextStatus.PENDING, textScript = null,
            )
        }.toMap()
        return rows to next
    }

    private fun renumbered(ordered: List<Page>) = ordered.mapIndexed { i, p -> p.id.value to p.copy(index = i) }.toMap()

    private fun pagesOf(s: State, documentId: Long) = s.pages.values.filter { it.documentId.value == documentId }.sortedBy { it.index }

    private fun subtree(s: State, root: Long): Set<Long> {
        val result = mutableSetOf(root)
        while (true) {
            val children = s.folders.values.filter { it.parentId in result && it.id !in result }.map { it.id }
            if (!result.addAll(children)) return result
        }
    }

    private fun folder(s: State, f: FolderRow) = Folder(
        FolderId(f.id), f.name, f.parentId?.let(::FolderId), f.createdAt,
        s.documents.values.count { it.folderId == f.id && it.trashedAt == null },
    )

    private fun document(s: State, d: DocRow) = Document(
        DocumentId(d.id), d.title, d.folderId?.let(::FolderId), d.createdAt, d.updatedAt, d.pinned, d.trashedAt, d.scriptOverride, pagesOf(s, d.id),
    )

    private fun summary(s: State, d: DocRow): DocumentSummary {
        val pages = pagesOf(s, d.id)
        return DocumentSummary(
            id = DocumentId(d.id), title = d.title, folderId = d.folderId?.let(::FolderId), createdAt = d.createdAt, updatedAt = d.updatedAt,
            pageCount = pages.size,
            storedBytes = pages.sumOf { p -> p.source.bytes + (p.render?.takeIf { it.path != p.source.path }?.bytes ?: 0) },
            coverThumbPath = pages.firstOrNull()?.thumbPath, pinned = d.pinned, trashedAt = d.trashedAt,
            textState = when {
                pages.any { it.textStatus == TextStatus.PENDING } -> TextState.PENDING
                pages.any { it.textStatus == TextStatus.FAILED } -> TextState.FAILED
                pages.any { it.textStatus == TextStatus.SKIPPED } -> TextState.OFF
                pages.any { it.textStatus == TextStatus.DONE } -> TextState.DONE
                else -> TextState.NONE
            },
        )
    }

    private fun order(sort: SortOrder): Comparator<DocumentSummary> = when (sort) {
        SortOrder.UPDATED_DESC -> compareByDescending<DocumentSummary> { it.updatedAt }
        SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
        SortOrder.TITLE_ASC -> compareBy { it.title.lowercase() }
        SortOrder.SIZE_DESC -> compareByDescending { it.storedBytes }
    }.thenByDescending { it.id.value }

    /** As Room: folders side by side may not share a name, ignoring case. */
    private fun requireFreeName(s: State, parentId: Long?, clean: String, except: Long?) {
        val key = clean.lowercase()
        if (s.folders.values.any { it.id != except && it.parentId == parentId && it.name.lowercase() == key }) throw FolderNameInUseException()
    }

    private fun cleanName(raw: String): String {
        val clean = nfc(raw).replace(Regex("\\s+"), " ").trim()
        require(clean.isNotEmpty()) { "name is blank" }
        return clean
    }

    private fun nfc(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)

    private companion object {
        const val JPEG = "image/jpeg"
        const val SNIPPET_RADIUS = 40
    }
}

/** [DocumentRepository] view of a [FakeLibrary]. */
class FakeDocumentRepository(val library: FakeLibrary = FakeLibrary()) : DocumentRepository by library, PageTextEditor by library

/** [PageWorkStore] view of a [FakeLibrary]; share the library with [FakeDocumentRepository] to see the same pages. */
class FakePageWorkStore(val library: FakeLibrary = FakeLibrary()) : PageWorkStore by library

/** [FolderRepository] view of a [FakeLibrary]. */
class FakeFolderRepository(val library: FakeLibrary = FakeLibrary()) : FolderRepository by library
