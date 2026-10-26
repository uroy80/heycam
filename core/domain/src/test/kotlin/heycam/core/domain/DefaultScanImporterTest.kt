package heycam.core.domain

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import heycam.core.common.Clock
import heycam.core.imaging.RejectReason
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImportTarget
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.ocr.LocaleScriptResolver
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultScanImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var library: InMemoryLibrary
    private lateinit var importer: FakeImporter
    private val sessions = RecordingSessions()

    @Before
    fun setUp() {
        library = InMemoryLibrary(tmp.newFolder("files"))
        importer = FakeImporter(tmp.newFolder("staging"))
    }

    private fun TestScope.subject(): Pair<DefaultScanImporter, QueuedPageProcessor> {
        val processor = QueuedPageProcessor(
            work = library, documents = library, settings = FakeSettings(), renderer = CopyingRenderer(), files = library,
            ocr = FakeOcr(), scripts = LocaleScriptResolver(), locales = { listOf(Locale.UK) }, network = {},
            clock = Clock { T0 }, log = SilentLog, dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler)),
            scope = backgroundScope,
        )
        val titles = DefaultTitle { _, _, _ -> "Scan 20 Oct 2026, 14:32" }
        return DefaultScanImporter(importer, library, library, sessions, processor, titles, Clock { T0 }, SilentLog) to processor
    }

    private fun staged(edits: PageEdits = PageEdits()) =
        StagedPage(StagedImage(tmp.newFile().apply { writeText("jpeg") }, 800, 1000, "image/jpeg", PageOrigin.SCANNER), edits)

    private fun session(target: ImportTarget, vararg pages: StagedPage) = ScanSession("s1", target, pages.toList(), T0)

    @Test
    fun stagesEachImageAndCountsRejects() = runTest {
        importer.reject = { if (it.lastPathSegment == "pdf") RejectReason.NOT_AN_IMAGE else null }
        importer.throwFor = { it.lastPathSegment == "crash" }
        val uris = listOf("a", "pdf", "b", "crash").map { Uri.parse("content://photos/$it") }
        val result = subject().first.stage(uris, PageOrigin.SHARE)
        assertThat(result.images).hasSize(2)
        assertThat(result.images.map { it.origin }.toSet()).containsExactly(PageOrigin.SHARE)
        assertThat(result.rejected).isEqualTo(2)
    }

    @Test
    fun atMostAHundredImagesAreStaged() = runTest {
        val uris = (1..105).map { Uri.parse("content://photos/$it") }
        val result = subject().first.stage(uris, PageOrigin.PHOTO_PICKER)
        assertThat(result.images).hasSize(100)
        assertThat(result.rejected).isEqualTo(5)
    }

    @Test
    fun newDocumentGetsTheDefaultTitleAndThePagesInOrder() = runTest {
        val (importer, processor) = subject()
        val result = importer.commit(session(ImportTarget.NewDocument(folderId = FolderId(7)), staged(), staged(PageEdits(filter = PageFilter.AUTO))))
        assertThat(library.title(result.documentId)).isEqualTo("Scan 20 Oct 2026, 14:32")
        assertThat(library.folder(result.documentId)).isEqualTo(FolderId(7))
        assertThat(result.pageIds).isEqualTo(library.pagesOf(result.documentId).map { it.id })
        assertThat(library.pagesOf(result.documentId)[1].edits.filter).isEqualTo(PageFilter.AUTO)
        assertThat(sessions.discarded).containsExactly("s1")
        processor.awaitIdle()
        assertThat(library.pagesOf(result.documentId).all { it.render != null }).isTrue()
    }

    @Test
    fun aSaveCancelledAsItCommitsStillDiscardsTheSessionAndWakesTheProcessor() = runTest {
        val (importer, processor) = subject()
        val saved = CompletableDeferred<Unit>()
        library.afterCreate = { saved.await() }
        val job = launch { importer.commit(session(ImportTarget.NewDocument(), staged())) }
        runCurrent()
        job.cancel()            // the user leaves Review just as the document is written
        saved.complete(Unit)
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(sessions.discarded).containsExactly("s1")
        processor.awaitIdle()
        val pages = library.pagesOf(DocumentId(1))
        assertThat(pages).hasSize(1)
        assertThat(pages.single().render).isNotNull()
    }

    @Test
    fun aGivenTitleIsKeptTrimmed() = runTest {
        val result = subject().first.commit(session(ImportTarget.NewDocument(title = "  Lease  "), staged()))
        assertThat(library.title(result.documentId)).isEqualTo("Lease")
    }

    @Test
    fun appendInsertsAtTheRequestedPlace() = runTest {
        val (importer, _) = subject()
        val doc = importer.commit(session(ImportTarget.NewDocument(), staged(), staged())).documentId
        val before = library.pagesOf(doc).map { it.id }
        val added = importer.commit(session(ImportTarget.AppendTo(doc, atIndex = 1), staged()))
        assertThat(added.documentId).isEqualTo(doc)
        assertThat(library.pagesOf(doc).map { it.id }).containsExactly(before[0], added.pageIds.single(), before[1]).inOrder()
    }

    @Test
    fun retakeReplacesThePageAndReportsItsDocument() = runTest {
        val (importer, processor) = subject()
        val doc = importer.commit(session(ImportTarget.NewDocument(), staged(), staged())).documentId
        processor.awaitIdle()
        val target = library.pagesOf(doc)[1]
        val result = importer.commit(session(ImportTarget.Replace(target.id), staged(PageEdits(filter = PageFilter.GRAYSCALE))))
        assertThat(result).isEqualTo(CommitResult(doc, listOf(target.id)))
        val replaced = library.page(target.id)
        assertThat(replaced.index).isEqualTo(1)
        assertThat(replaced.source.path).isNotEqualTo(target.source.path)
        assertThat(replaced.edits.filter).isEqualTo(PageFilter.GRAYSCALE)
    }

    @Test
    fun anEmptySessionIsRefusedAndKept() = runTest {
        val failure = runCatching { subject().first.commit(session(ImportTarget.NewDocument())) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sessions.discarded).isEmpty()
    }

    @Test
    fun aMissingAppendTargetFailsWithoutDiscardingTheSession() = runTest {
        val importer = subject().first
        val failure = runCatching { importer.commit(session(ImportTarget.AppendTo(DocumentId(404)), staged())) }
        assertThat(failure.isFailure).isTrue()
        assertThat(sessions.discarded).isEmpty()
    }
}
