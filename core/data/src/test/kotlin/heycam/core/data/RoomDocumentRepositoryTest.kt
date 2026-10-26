package heycam.core.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.Rotation
import heycam.core.model.SortOrder
import heycam.core.model.TextState
import heycam.core.model.TextStatus
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomDocumentRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var rig: DataTestRig
    private val repo get() = rig.documents

    @Before fun setUp() { rig = DataTestRig(temp.root) }
    @After fun tearDown() = rig.close()

    private suspend fun document(id: DocumentId): Document = requireNotNull(repo.observeDocument(id).first())

    private suspend fun create(title: String = "Scan", pages: Int = 2, folder: FolderId? = null): DocumentId =
        repo.createDocument(title, folder, List(pages) { rig.staged() })

    /** Renders a page the way PageProcessor does: a render file and a thumbnail inside the page directory. */
    private suspend fun render(page: Page, separate: Boolean = true): Boolean {
        val dir = rig.files.pageDir(page.fileKey)
        val render = if (separate) File(dir, "render-${page.editsVersion}.jpg").apply { writeBytes(ByteArray(500)) } else File(page.source.path)
        val thumb = File(dir, "thumb-${page.editsVersion}.jpg").apply { writeBytes(ByteArray(50)) }
        return repo.saveRender(page.id, page.editsVersion, ImageInfo(render.path, 90, 140, render.length(), "image/jpeg"), thumb.path)
    }

    // ---- create / add / replace -------------------------------------------------------------------------------

    @Test
    fun createMovesStagedFilesIntoPageDirectories() = runTest {
        val staged = List(3) { rig.staged(bytes = 1000 + it) }
        val id = repo.createDocument("  Lease \n  2026 ", null, staged)
        val doc = document(id)
        assertThat(doc.title).isEqualTo("Lease 2026")
        assertThat(doc.createdAt).isEqualTo(rig.clock.now)
        assertThat(doc.pages.map { it.index }).containsExactly(0, 1, 2).inOrder()
        doc.pages.forEachIndexed { i, page ->
            assertThat(page.source.path).isEqualTo(File(rig.files.pageDir(page.fileKey), "source.jpg").absolutePath)
            assertThat(File(page.source.path).length()).isEqualTo(1000L + i)
            assertThat(page.source.bytes).isEqualTo(1000L + i)
            assertThat(page.editsVersion).isEqualTo(0)
            assertThat(page.render).isNull()
            assertThat(page.textStatus).isEqualTo(TextStatus.PENDING)
        }
        assertThat(staged.none { it.image.file.exists() }).isTrue()
    }

    @Test
    fun createRejectsBadInputAndLeavesNothingBehind() = runTest {
        val page = rig.staged()
        assertThat(runCatching { repo.createDocument("   ", null, listOf(page)) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repo.createDocument("Scan", null, emptyList()) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        val missing = rig.staged().also { it.image.file.delete() }
        assertThat(runCatching { repo.createDocument("Scan", null, listOf(page, missing)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(page.image.file.exists()).isTrue()
        assertThat(repo.observeStats().first().documents).isEqualTo(0)
        assertThat(rig.dirs.pages.list().orEmpty()).isEmpty()
    }

    @Test
    fun createFilesIntoAGoneFolderAtTheRoot() = runTest {
        val id = create(folder = FolderId(42))
        assertThat(document(id).folderId).isNull()
    }

    @Test
    fun addPagesInsertsAtIndexAndAppends() = runTest {
        val id = create(pages = 2)
        val before = document(id).pages
        rig.clock.advance(Duration.ofMinutes(1))
        val inserted = repo.addPages(id, listOf(rig.staged(), rig.staged()), atIndex = 1)
        val appended = repo.addPages(id, listOf(rig.staged()))
        val pages = document(id).pages
        assertThat(pages.map { it.id }).containsExactly(before[0].id, inserted[0], inserted[1], before[1].id, appended[0]).inOrder()
        assertThat(pages.map { it.index }).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(document(id).updatedAt).isEqualTo(rig.clock.now)
        assertThat(repo.addPages(id, emptyList())).isEmpty()
        assertThat(runCatching { repo.addPages(DocumentId(999), listOf(rig.staged())) }.exceptionOrNull())
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun replacePageKeepsIdAndPositionWithNewSourceAndText() = runTest {
        val id = create(pages = 2)
        val old = document(id).pages[1]
        render(old)
        repo.saveText(pageText(old.id, old.editsVersion, "Old words"))
        val fresh = rig.staged(width = 300, height = 400, edits = PageEdits(filter = PageFilter.AUTO))
        repo.replacePage(old.id, fresh)
        val page = document(id).pages[1]
        assertThat(page.id).isEqualTo(old.id)
        assertThat(page.index).isEqualTo(1)
        assertThat(page.fileKey).isNotEqualTo(old.fileKey)
        assertThat(page.source.widthPx to page.source.heightPx).isEqualTo(300 to 400)
        assertThat(page.edits.filter).isEqualTo(PageFilter.AUTO)
        assertThat(page.editsVersion).isEqualTo(old.editsVersion + 1)
        assertThat(page.render).isNull()
        assertThat(page.textStatus).isEqualTo(TextStatus.PENDING)
        assertThat(rig.files.pageDir(old.fileKey).exists()).isFalse()
        assertThat(repo.observeText(id).first()).isEmpty()
        assertThat(repo.search(heycam.core.model.SearchQuery("Old words"))).isEmpty()
    }

    // ---- cancelled saves ---------------------------------------------------------------------------------------

    /**
     * Runs [save] in its own job and cancels that job right after the database has run a statement containing each
     * of [steps], in this order; the cancel lands on the last one. Returns once the job has finished.
     */
    private suspend fun TestScope.cancelledAt(vararg steps: String, save: suspend () -> Unit) {
        val reached = AtomicInteger()
        lateinit var job: Job
        rig.driver.afterStep = { sql ->
            val step = reached.get()
            if (step < steps.size && sql.contains(steps[step])) {
                reached.incrementAndGet()
                if (step == steps.lastIndex) job.cancel()
            }
        }
        job = launch { save() }
        job.join()
        rig.driver.afterStep = null
        assertWithMessage("statements reached of ${steps.toList()}").that(reached.get()).isEqualTo(steps.size)
        assertThat(job.isCancelled).isTrue()
    }

    private suspend fun allPages(): List<Page> =
        repo.observeLibrary(null, SortOrder.UPDATED_DESC).first().documents.flatMap { document(it.id).pages }

    private suspend fun assertEveryPageHasItsSource() {
        for (page in allPages()) assertWithMessage("source of page ${page.id}").that(File(page.source.path).isFile).isTrue()
    }

    @Test
    fun createCancelledAsItCommitsKeepsEveryPageWithItsFile() = runTest {
        val staged = List(3) { rig.staged() }
        cancelledAt(PAGE_INSERT, COMMIT) { repo.createDocument("Scan", null, staged) }
        assertThat(allPages()).hasSize(3)
        assertEveryPageHasItsSource()
        assertThat(staged.none { it.image.file.exists() }).isTrue()
    }

    @Test
    fun addPagesCancelledAsItCommitsKeepsTheNewFiles() = runTest {
        val id = create(pages = 1)
        val staged = List(2) { rig.staged() }
        cancelledAt(PAGE_INSERT, COMMIT) { repo.addPages(id, staged) }
        assertThat(document(id).pages).hasSize(3)
        assertEveryPageHasItsSource()
        assertThat(staged.none { it.image.file.exists() }).isTrue()
    }

    @Test
    fun replacePageCancelledAsItCommitsKeepsTheNewSourceAndDropsTheOld() = runTest {
        val id = create(pages = 2)
        val old = document(id).pages[1]
        val fresh = rig.staged(bytes = 1234)
        cancelledAt(PAGE_UPDATE, COMMIT) { repo.replacePage(old.id, fresh) }
        val page = document(id).pages[1]
        assertThat(page.fileKey).isNotEqualTo(old.fileKey)
        assertThat(File(page.source.path).length()).isEqualTo(1234)
        assertEveryPageHasItsSource()
        assertThat(fresh.image.file.exists()).isFalse()
        assertThat(rig.files.pageDir(old.fileKey).exists()).isFalse()
    }

    @Test
    fun createCancelledMidwaySavesAllOrNothing() = runTest {
        val staged = List(3) { rig.staged() }
        cancelledAt(DOCUMENT_INSERT) { repo.createDocument("Scan", null, staged) }
        val pages = allPages()
        if (pages.isEmpty()) {
            assertThat(repo.observeStats().first().documents).isEqualTo(0)
            assertThat(staged.all { it.image.file.isFile }).isTrue()
            assertThat(rig.dirs.pages.list().orEmpty()).isEmpty()
        } else {
            assertThat(pages).hasSize(3)
            assertEveryPageHasItsSource()
            assertThat(staged.none { it.image.file.exists() }).isTrue()
        }
    }

    // ---- edits -------------------------------------------------------------------------------------------------

    @Test
    fun updateEditsBumpsVersionsAndInvalidatesTextOnlyOnGeometry() = runTest {
        val id = create(pages = 3)
        val pages = document(id).pages
        pages.forEach { render(it); repo.saveText(pageText(it.id, it.editsVersion, "text ${it.index}")) }
        repo.setTextStatus(pages[2].id, pages[2].editsVersion, TextStatus.SKIPPED)

        repo.updateEdits(pages.map { it.id }) { it.copy(filter = PageFilter.GRAYSCALE) }
        var now = document(id).pages
        assertThat(now.map { it.editsVersion }).containsExactly(1, 1, 1)
        assertThat(now.map { it.textStatus }).containsExactly(TextStatus.DONE, TextStatus.DONE, TextStatus.SKIPPED).inOrder()
        assertThat(now.all { it.render == null }).isTrue()

        repo.updateEdits(listOf(pages[0].id, pages[2].id)) { it.copy(rotation = it.rotation.clockwise()) }
        now = document(id).pages
        assertThat(now.map { it.editsVersion }).containsExactly(2, 1, 2).inOrder()
        assertThat(now[0].edits).isEqualTo(PageEdits(rotation = Rotation.R90, filter = PageFilter.GRAYSCALE))
        assertThat(now.map { it.textStatus }).containsExactly(TextStatus.PENDING, TextStatus.DONE, TextStatus.SKIPPED).inOrder()

        repo.updateEdits(listOf(pages[1].id)) { it }
        assertThat(document(id).pages[1].editsVersion).isEqualTo(1)
    }

    @Test
    fun movePageReorders() = runTest {
        val id = create(pages = 4)
        val ids = document(id).pages.map { it.id }
        repo.movePage(id, 0, 2)
        assertThat(document(id).pages.map { it.id }).containsExactly(ids[1], ids[2], ids[0], ids[3]).inOrder()
        repo.movePage(id, 3, 0)
        assertThat(document(id).pages.map { it.id }).containsExactly(ids[3], ids[1], ids[2], ids[0]).inOrder()
        assertThat(runCatching { repo.movePage(id, 0, 4) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun deletePagesRemovesFilesAndRenumbers() = runTest {
        val id = create(pages = 4)
        val pages = document(id).pages
        repo.deletePages(listOf(pages[1].id, pages[2].id, PageId(999)))
        val left = document(id).pages
        assertThat(left.map { it.id }).containsExactly(pages[0].id, pages[3].id).inOrder()
        assertThat(left.map { it.index }).containsExactly(0, 1).inOrder()
        assertThat(rig.files.pageDir(pages[1].fileKey).exists()).isFalse()
        assertThat(rig.files.pageDir(pages[0].fileKey).exists()).isTrue()
    }

    // ---- document fields, folders, trash ------------------------------------------------------------------------

    @Test
    fun renamePinScriptAndMove() = runTest {
        val id = create()
        val folder = rig.folders.create("Bills", null)
        repo.rename(id, "Gas bill")
        repo.setPinned(id, true)
        repo.setScriptOverride(id, OcrScript.DEVANAGARI)
        repo.moveToFolder(listOf(id), folder)
        val doc = document(id)
        assertThat(doc.title).isEqualTo("Gas bill")
        assertThat(doc.pinned).isTrue()
        assertThat(doc.scriptOverride).isEqualTo(OcrScript.DEVANAGARI)
        assertThat(doc.folderId).isEqualTo(folder)
        repo.setScriptOverride(id, null)
        repo.moveToFolder(listOf(id), null)
        assertThat(document(id).scriptOverride).isNull()
        assertThat(document(id).folderId).isNull()
        assertThat(runCatching { repo.moveToFolder(listOf(id), FolderId(999)) }.exceptionOrNull())
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(runCatching { repo.rename(id, " ") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun trashHidesDocumentsUntilRestored() = runTest {
        val keep = create("Keep")
        val first = create("First")
        val second = create("Second")
        repo.trash(listOf(first))
        rig.clock.advance(Duration.ofMinutes(5))
        repo.trash(listOf(second))
        assertThat(repo.observeTrash().first().map { it.id }).containsExactly(second, first).inOrder()
        assertThat(repo.observeTrash().first()[0].trashedAt).isEqualTo(rig.clock.now)
        assertThat(repo.observeLibrary(null, SortOrder.UPDATED_DESC).first().documents.map { it.id }).containsExactly(keep)
        assertThat(repo.observeRecent().first().map { it.id }).containsExactly(keep)
        assertThat(repo.observeStats().first().documents).isEqualTo(1)
        assertThat(repo.search(heycam.core.model.SearchQuery("First"))).isEmpty()
        assertThat(repo.pagesNeedingRender(10).map { it.documentId }.toSet()).containsExactly(keep)

        repo.restore(listOf(first))
        assertThat(repo.observeTrash().first().map { it.id }).containsExactly(second)
        assertThat(document(first).trashedAt).isNull()
    }

    @Test
    fun deleteForeverAndPurgeRemoveRowsAndFiles() = runTest {
        val old = create("Old")
        val oldKeys = document(old).pages.map { it.fileKey }
        repo.trash(listOf(old))
        rig.clock.advance(Duration.ofDays(31))
        val recent = create("Recent")
        repo.trash(listOf(recent))
        val gone = create("Gone")
        val goneKeys = document(gone).pages.map { it.fileKey }

        assertThat(repo.purgeTrash(rig.clock.now.minus(Duration.ofDays(30)))).isEqualTo(1)
        assertThat(repo.observeDocument(old).first()).isNull()
        assertThat(oldKeys.none { rig.files.pageDir(it).exists() }).isTrue()
        assertThat(repo.observeDocument(recent).first()).isNotNull()

        repo.deleteForever(listOf(gone))
        assertThat(repo.observeDocument(gone).first()).isNull()
        assertThat(goneKeys.none { rig.files.pageDir(it).exists() }).isTrue()
        assertThat(repo.purgeTrash(rig.clock.now.minus(Duration.ofDays(30)))).isEqualTo(0)
    }

    // ---- observation -------------------------------------------------------------------------------------------

    @Test
    fun librarySnapshotHasBreadcrumbsFoldersAndSortedDocuments() = runTest {
        val top = rig.folders.create("Work", null)
        val mid = rig.folders.create("Tax", top)
        rig.folders.create("beta", mid)
        rig.folders.create("Alpha", mid)
        val a = repo.createDocument("b title", mid, listOf(rig.staged(bytes = 10)))
        rig.clock.advance(Duration.ofMinutes(1))
        val b = repo.createDocument("A title", mid, listOf(rig.staged(bytes = 30)))
        rig.clock.advance(Duration.ofMinutes(1))
        repo.rename(a, "c title")

        val snapshot = repo.observeLibrary(mid, SortOrder.UPDATED_DESC).first()
        assertThat(snapshot.folder?.id).isEqualTo(mid)
        assertThat(snapshot.breadcrumbs.map { it.name }).containsExactly("Work", "Tax").inOrder()
        assertThat(snapshot.folders.map { it.name }).containsExactly("Alpha", "beta").inOrder()
        assertThat(snapshot.documents.map { it.id }).containsExactly(a, b).inOrder()
        assertThat(repo.observeLibrary(mid, SortOrder.CREATED_DESC).first().documents.map { it.id }).containsExactly(b, a).inOrder()
        assertThat(repo.observeLibrary(mid, SortOrder.TITLE_ASC).first().documents.map { it.title }).containsExactly("A title", "c title").inOrder()
        assertThat(repo.observeLibrary(mid, SortOrder.SIZE_DESC).first().documents.map { it.id }).containsExactly(b, a).inOrder()

        val root = repo.observeLibrary(null, SortOrder.UPDATED_DESC).first()
        assertThat(root.folder).isNull()
        assertThat(root.breadcrumbs).isEmpty()
        assertThat(root.folders.map { it.name }).containsExactly("Work")
        assertThat(root.folders.single().documentCount).isEqualTo(0)

        assertThat(repo.observeLibrary(FolderId(999), SortOrder.UPDATED_DESC).first().documents).isEmpty()
    }

    @Test
    fun libraryUpdatesLive() = runTest {
        repo.observeLibrary(null, SortOrder.UPDATED_DESC).test {
            assertThat(awaitItem().documents).isEmpty()
            val id = create("Live")
            assertThat(awaitItem().documents.map { it.id }).containsExactly(id)
            repo.trash(listOf(id))
            assertThat(awaitItem().documents).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun summariesCountStoredBytesCoverAndTextState() = runTest {
        val id = repo.createDocument("Scan", null, listOf(rig.staged(bytes = 1000), rig.staged(bytes = 2000)))
        var summary = repo.observeRecent().first().single()
        assertThat(summary.pageCount).isEqualTo(2)
        assertThat(summary.storedBytes).isEqualTo(3000)
        assertThat(summary.coverThumbPath).isNull()
        assertThat(summary.textState).isEqualTo(TextState.PENDING)

        val pages = document(id).pages
        render(pages[0], separate = true)
        render(pages[1], separate = false)
        repo.saveText(pageText(pages[0].id, 0, "Hello"))
        repo.setTextStatus(pages[1].id, 0, TextStatus.FAILED)
        summary = repo.observeRecent().first().single()
        assertThat(summary.storedBytes).isEqualTo(3000 + 500)
        assertThat(summary.coverThumbPath).isEqualTo(File(rig.files.pageDir(pages[0].fileKey), "thumb-0.jpg").absolutePath)
        assertThat(summary.textState).isEqualTo(TextState.FAILED)
        assertThat(repo.observeStats().first()).isEqualTo(heycam.core.model.LibraryStats(documents = 1, pages = 2, storedBytes = 3500))

        repo.setTextStatus(pages[1].id, 0, TextStatus.SKIPPED)
        assertThat(repo.observeRecent().first().single().textState).isEqualTo(TextState.OFF)
        repo.setTextStatus(pages[1].id, 0, TextStatus.EMPTY)
        assertThat(repo.observeRecent().first().single().textState).isEqualTo(TextState.DONE)
    }

    @Test
    fun documentWithNoTextOnAnyPageIsNotSummarisedAsText() = runTest {
        val id = create(pages = 2)
        val pages = document(id).pages
        pages.forEach { repo.setTextStatus(it.id, it.editsVersion, TextStatus.EMPTY) }
        assertThat(repo.observeRecent().first().single().textState).isEqualTo(TextState.NONE)
        assertThat(repo.observeLibrary(null, SortOrder.UPDATED_DESC).first().documents.single().textState).isEqualTo(TextState.NONE)

        repo.saveText(pageText(pages[0].id, pages[0].editsVersion, "Hello"))
        assertThat(repo.observeRecent().first().single().textState).isEqualTo(TextState.DONE)
        repo.saveText(pageText(pages[0].id, pages[0].editsVersion, " "))
        assertThat(repo.observeRecent().first().single().textState).isEqualTo(TextState.NONE)
    }

    @Test
    fun recentIsNewestFirstAndLimited() = runTest {
        val ids = List(7) { create("Doc $it").also { rig.clock.advance(Duration.ofMinutes(1)) } }
        assertThat(repo.observeRecent().first().map { it.id }).containsExactlyElementsIn(ids.reversed().take(5)).inOrder()
        assertThat(repo.observeRecent(limit = 2).first().map { it.id }).containsExactly(ids[6], ids[5]).inOrder()
    }

    // ---- PageWorkStore ------------------------------------------------------------------------------------------

    @Test
    fun renderWorkIsVersionCheckedAndCleansSupersededFiles() = runTest {
        val id = create(pages = 1)
        val page = document(id).pages.single()
        assertThat(repo.pagesNeedingRender(10).map { it.id }).containsExactly(page.id)
        assertThat(repo.pagesNeedingText(10)).isEmpty()

        val dir = rig.files.pageDir(page.fileKey)
        val stale = ImageInfo(File(dir, "render-7.jpg").path, 1, 1, 1, "image/jpeg")
        assertThat(repo.saveRender(page.id, 7, stale, File(dir, "thumb-7.jpg").path)).isFalse()
        assertThat(render(page)).isTrue()
        val rendered = document(id).pages.single()
        assertThat(rendered.render?.path).endsWith("render-0.jpg")
        assertThat(rendered.thumbPath).endsWith("thumb-0.jpg")
        assertThat(repo.pagesNeedingRender(10)).isEmpty()
        assertThat(repo.pagesNeedingText(10).map { it.id }).containsExactly(page.id)

        repo.updateEdits(listOf(page.id)) { it.copy(filter = PageFilter.BLACK_WHITE) }
        val edited = document(id).pages.single()
        assertThat(edited.render).isNull()
        assertThat(edited.thumbPath).endsWith("thumb-0.jpg")
        assertThat(render(edited)).isTrue()
        assertThat(dir.list()!!.toSet()).containsExactly("source.jpg", "render-1.jpg", "thumb-1.jpg")

        val outside = File(temp.root, "elsewhere.jpg").apply { writeBytes(ByteArray(1)) }
        val error = runCatching {
            repo.saveRender(page.id, 1, ImageInfo(outside.path, 1, 1, 1, "image/jpeg"), File(dir, "thumb-1.jpg").path)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun textWorkIsVersionCheckedAndNormalised() = runTest {
        val id = create(pages = 2)
        val (first, second) = document(id).pages
        render(first)
        render(second)
        val decomposed = "Café ok"
        assertThat(repo.saveText(pageText(first.id, 5, decomposed))).isFalse()
        assertThat(repo.saveText(pageText(first.id, 0, decomposed, script = OcrScript.LATIN))).isTrue()
        assertThat(repo.saveText(pageText(second.id, 0, "  \n"))).isTrue()
        val pages = document(id).pages
        assertThat(pages[0].textStatus).isEqualTo(TextStatus.DONE)
        assertThat(pages[0].textScript).isEqualTo(OcrScript.LATIN)
        assertThat(pages[1].textStatus).isEqualTo(TextStatus.EMPTY)
        val texts = repo.observeText(id).first()
        assertThat(texts.map { it.pageId }).containsExactly(first.id, second.id).inOrder()
        assertThat(texts[0].text).isEqualTo("Café ok")
        assertThat(texts[0].lines.single().text).isEqualTo("Café ok")
        assertThat(texts[0].lines.single().box).isEqualTo(heycam.core.model.BoxPx(1f, 2f, 30f, 12f))

        assertThat(repo.setTextStatus(first.id, 3, TextStatus.FAILED)).isFalse()
        assertThat(repo.setTextStatus(first.id, 0, TextStatus.FAILED)).isTrue()
        repo.markTextPending(id)
        assertThat(document(id).pages.map { it.textStatus }).containsExactly(TextStatus.PENDING, TextStatus.PENDING)
        assertThat(repo.pagesNeedingText(1)).hasSize(1)
    }

    @Test
    fun editedTextReplacesSearchTextAndKeepsGeometry() = runTest {
        val id = create(pages = 2)
        val (first, second) = document(id).pages
        render(first)
        repo.saveText(pageText(first.id, 0, "Invoice 2026"))
        repo.updateText(first.id, "Receipt 2026")
        val text = repo.observeText(id).first().single()
        assertThat(text.text).isEqualTo("Receipt 2026")
        assertThat(text.lines.single().text).isEqualTo("Invoice 2026")
        assertThat(repo.search(heycam.core.model.SearchQuery("Invoice"))).isEmpty()
        assertThat(repo.search(heycam.core.model.SearchQuery("Receipt"))).hasSize(1)

        repo.updateText(second.id, "Typed by hand")
        val typed = repo.observeText(id).first().first { it.pageId == second.id }
        assertThat(typed.lines).isEmpty()
        assertThat(typed.imageWidthPx to typed.imageHeightPx).isEqualTo(100 to 150)
        assertThat(document(id).pages[1].textStatus).isEqualTo(TextStatus.DONE)

        repo.updateText(second.id, " ")
        assertThat(document(id).pages[1].textStatus).isEqualTo(TextStatus.EMPTY)
        assertThat(runCatching { repo.updateText(PageId(999), "x") }.exceptionOrNull()).isInstanceOf(NoSuchElementException::class.java)
    }

    private fun pageText(page: PageId, version: Int, text: String, script: OcrScript = OcrScript.LATIN) = heycam.core.model.PageText(
        pageId = page,
        script = script,
        text = text,
        lines = if (text.isBlank()) emptyList() else listOf(heycam.core.model.TextLine(text, heycam.core.model.BoxPx(1f, 2f, 30f, 12f), 0f, 0.9f)),
        imageWidthPx = 90,
        imageHeightPx = 140,
        editsVersion = version,
        engine = "mlkit-text-v2/19.0.1",
        recognizedAt = rig.clock.now,
    )

    private companion object {
        // Parts of the SQL Room runs, for the statement hook.
        const val DOCUMENT_INSERT = "INTO `documents`"
        const val PAGE_INSERT = "INTO `pages`"
        const val PAGE_UPDATE = "`pages` SET"
        const val COMMIT = "END TRANSACTION"
    }
}
