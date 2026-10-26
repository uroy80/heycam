package heycam.core.testing

import android.graphics.BitmapFactory
import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.data.FolderNameInUseException
import heycam.core.data.FolderTooDeepException
import heycam.core.imaging.ImportedImage
import heycam.core.imaging.RejectReason
import heycam.core.imaging.RenderSpec
import heycam.core.model.DocumentId
import heycam.core.model.ImageInfo
import heycam.core.model.ImportTarget
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.Rotation
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.SortOrder
import heycam.core.model.StagedPage
import heycam.core.model.TextState
import heycam.core.model.TextStatus
import heycam.core.testing.common.RecordingLog
import heycam.core.testing.common.TestClock
import heycam.core.testing.data.FakeDocumentRepository
import heycam.core.testing.data.FakeFileStore
import heycam.core.testing.data.FakeFolderRepository
import heycam.core.testing.data.FakeLibrary
import heycam.core.testing.data.FakePageWorkStore
import heycam.core.testing.data.FakeScanSessionStore
import heycam.core.testing.data.FakeSettingsRepository
import heycam.core.testing.imaging.FakeImageImporter
import heycam.core.testing.imaging.FakePageRenderer
import heycam.core.testing.imaging.Sample
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FakesTest {
    @get:Rule val temp = TemporaryFolder()
    private val clock = TestClock()
    private val library = FakeLibrary(clock)
    private val documents = FakeDocumentRepository(library)
    private val work = FakePageWorkStore(library)
    private val folders = FakeFolderRepository(library)
    private val files by lazy { FakeFileStore(temp.root) }

    @Test
    fun seededDocumentsShowInTheLibraryAndSearch() = runTest {
        val folder = folders.create("Bills", null)
        val id = library.seed("Gas bill", pageCount = 2, folderId = folder, texts = listOf("Amount due 42", "Thank you"))
        val snapshot = documents.observeLibrary(folder, SortOrder.UPDATED_DESC).first()
        assertThat(snapshot.breadcrumbs.map { it.name }).containsExactly("Bills")
        assertThat(snapshot.documents.single().pageCount).isEqualTo(2)
        assertThat(snapshot.documents.single().textState).isEqualTo(TextState.DONE)
        val hits = documents.search(SearchQuery("due"))
        val text = hits.filterIsInstance<SearchHit.TextHit>().single()
        assertThat(text.document.id).isEqualTo(id)
        assertThat(text.snippet.text.substring(text.snippet.matches.single().first, text.snippet.matches.single().last + 1)).isEqualTo("due")
        assertThat(documents.search(SearchQuery("gas")).filterIsInstance<SearchHit.TitleHit>().single().match).isEqualTo(0..2)
    }

    @Test
    fun summaryTextStateFollowsThePagesLikeTheRealRepository() = runTest {
        val id = library.seed("Photo", pageCount = 2)
        val pages = documents.observeDocument(id).first()!!.pages
        suspend fun state() = documents.observeRecent().first().single().textState
        assertThat(state()).isEqualTo(TextState.PENDING)
        pages.forEach { work.setTextStatus(it.id, it.editsVersion, TextStatus.EMPTY) }
        assertThat(state()).isEqualTo(TextState.NONE)
        work.setTextStatus(pages[0].id, pages[0].editsVersion, TextStatus.DONE)
        assertThat(state()).isEqualTo(TextState.DONE)
        work.setTextStatus(pages[1].id, pages[1].editsVersion, TextStatus.SKIPPED)
        assertThat(state()).isEqualTo(TextState.OFF)
        work.setTextStatus(pages[1].id, pages[1].editsVersion, TextStatus.FAILED)
        assertThat(state()).isEqualTo(TextState.FAILED)
    }

    @Test
    fun createdPagesFlowThroughRenderAndText() = runTest {
        val staged = Sample.EN_HI.stage(files)
        val id = documents.createDocument(" New  scan ", null, listOf(StagedPage(staged, PageEdits(filter = PageFilter.AUTO))))
        val page = documents.observeDocument(id).first()!!.pages.single()
        assertThat(documents.observeDocument(id).first()!!.title).isEqualTo("New scan")
        assertThat(page.source.path).isEqualTo(staged.file.absolutePath)
        assertThat(work.pagesNeedingRender(5).map { it.id }).containsExactly(page.id)
        assertThat(work.saveRender(page.id, 3, page.source, "/t.jpg")).isFalse()
        assertThat(work.saveRender(page.id, 0, page.source, "/t.jpg")).isTrue()
        assertThat(work.pagesNeedingText(5).map { it.id }).containsExactly(page.id)

        documents.updateEdits(listOf(page.id)) { it.copy(rotation = Rotation.R90) }
        val edited = documents.observeDocument(id).first()!!.pages.single()
        assertThat(edited.editsVersion).isEqualTo(1)
        assertThat(edited.render).isNull()
        assertThat(edited.textStatus).isEqualTo(TextStatus.PENDING)

        documents.updateText(page.id, "typed")
        assertThat(documents.observeText(id).first().single().text).isEqualTo("typed")
    }

    @Test
    fun pagesReorderTrashRestoreAndPurge() = runTest {
        val id = library.seed("Doc", pageCount = 3)
        val ids = documents.observeDocument(id).first()!!.pages.map { it.id }
        documents.movePage(id, 2, 0)
        documents.deletePages(listOf(ids[1]))
        val pages = documents.observeDocument(id).first()!!.pages
        assertThat(pages.map { it.id }).containsExactly(ids[2], ids[0]).inOrder()
        assertThat(pages.map { it.index }).containsExactly(0, 1).inOrder()

        documents.observeTrash().test {
            assertThat(awaitItem()).isEmpty()
            documents.trash(listOf(id))
            assertThat(awaitItem().single().trashedAt).isEqualTo(clock.now)
            clock.advance(Duration.ofDays(31))
            assertThat(documents.purgeTrash(clock.now.minus(Duration.ofDays(30)))).isEqualTo(1)
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(documents.observeDocument(id).first()).isNull()
    }

    @Test
    fun foldersFollowTheRealRules() = runTest {
        val a = folders.create("A", null)
        val b = folders.create("B", a)
        val c = folders.create("C", b)
        assertThat(runCatching { folders.create("D", c) }.exceptionOrNull()).isInstanceOf(FolderTooDeepException::class.java)
        assertThat(runCatching { folders.create(" a ", null) }.exceptionOrNull()).isInstanceOf(FolderNameInUseException::class.java)
        assertThat(runCatching { folders.rename(c, "b") }.exceptionOrNull()).isNull() // c sits inside B, not beside it
        val doc = library.seed("In B", folderId = b)
        folders.delete(b, moveDocumentsTo = null)
        assertThat(folders.observeFolders(a).first().map { it.id }).containsExactly(c)
        assertThat(documents.observeDocument(doc).first()!!.folderId).isNull()
    }

    @Test
    fun failWithMakesCallsThrow() = runTest {
        library.failWith = IOException("disk full")
        assertThat(runCatching { documents.trash(listOf(DocumentId(1))) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun settingsAndSessions() = runTest {
        val settings = FakeSettingsRepository()
        settings.update { it.copy(autoText = false) }
        assertThat(settings.current.autoText).isFalse()

        val sessions = FakeScanSessionStore(clock, defaultFilter = PageFilter.GRAYSCALE)
        val session = sessions.create(ImportTarget.NewDocument(), listOf(Sample.BLANK.stage(files)))
        assertThat(session.pages.single().edits.filter).isEqualTo(PageFilter.GRAYSCALE)
        sessions.update(session.copy(pages = emptyList()))
        assertThat(sessions.get(session.id)!!.pages).isEmpty()
        sessions.discard(session.id)
        assertThat(sessions.get(session.id)).isNull()
        assertThat(sessions.discarded).containsExactly(session.id)
    }

    @Test
    fun fileStoreAndSamples() = runTest {
        val staged = Sample.EN_HI.stage(files, PageOrigin.SHARE)
        assertThat(staged.file.parentFile).isEqualTo(File(temp.root, "staging/loose"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(staged.file.path, bounds)
        assertThat(bounds.outWidth to bounds.outHeight).isEqualTo(Sample.EN_HI.widthPx to Sample.EN_HI.heightPx)
        assertThat(files.usage().cacheBytes).isEqualTo(staged.file.length())
        files.sweep()
        assertThat(files.sweeps).isEqualTo(1)
    }

    @Test
    fun rendererCopiesAndReportsTheRealSize() = runTest {
        val source = Sample.BLANK.copyTo(File(temp.root, "source.jpg"))
        val info = ImageInfo(source.path, 1240, 1754, source.length(), "image/jpeg")
        val renderer = FakePageRenderer()
        val out = renderer.render(info, PageEdits(rotation = Rotation.R90), RenderSpec(maxLongEdgePx = 877), File(temp.root, "r.jpg"))
        assertThat(out.widthPx to out.heightPx).isEqualTo(877 to 620)
        assertThat(File(out.path).length()).isEqualTo(source.length())
        assertThat(renderer.renders.single().edits.rotation).isEqualTo(Rotation.R90)
        assertThat(renderer.preview(info, PageEdits(), 100).let { it.width to it.height }).isEqualTo(71 to 100)
    }

    @Test
    fun importerStagesTheSampleOrRejects() = runTest {
        val importer = FakeImageImporter(files)
        val good = Uri.parse("content://picker/1")
        val bad = Uri.parse("content://picker/2")
        importer.rejections[bad] = RejectReason.TOO_LARGE
        val ok = importer.import(good, PageOrigin.PHOTO_PICKER) as ImportedImage.Ok
        assertThat(ok.image.file.isFile).isTrue()
        assertThat(ok.image.origin).isEqualTo(PageOrigin.PHOTO_PICKER)
        assertThat(importer.import(bad, PageOrigin.SHARE)).isEqualTo(ImportedImage.Rejected(RejectReason.TOO_LARGE))
        assertThat(importer.imported).containsExactly(good, bad).inOrder()
    }

    @Test
    fun recordingLogKeepsLines() {
        val log = RecordingLog()
        log.w("tag", "message", IOException())
        assertThat(log.entries.single().level).isEqualTo('W')
    }
}
