package heycam.core.domain

import com.google.common.truth.Truth.assertThat
import heycam.core.common.Clock
import heycam.core.model.BoxPx
import heycam.core.model.DocumentId
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.Rotation
import heycam.core.model.Settings
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.model.TextLine
import heycam.core.model.TextStatus
import heycam.core.model.UnsupportedReason
import heycam.core.ocr.LocaleScriptResolver
import heycam.core.ocr.OcrOutcome
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedPageProcessorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var library: InMemoryLibrary
    private val settings = FakeSettings()
    private val renderer = CopyingRenderer()
    private val ocr = FakeOcr()
    private var locales = listOf(Locale.UK)
    private val online = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        library = InMemoryLibrary(tmp.newFolder("files"))
    }

    private fun TestScope.processor(scope: CoroutineScope = backgroundScope): QueuedPageProcessor {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return QueuedPageProcessor(
            work = library,
            documents = library,
            settings = settings,
            renderer = renderer,
            files = library,
            ocr = ocr,
            scripts = LocaleScriptResolver(),
            locales = { locales },
            network = { online.await() },
            clock = Clock { T0 },
            log = SilentLog,
            dispatchers = TestDispatchers(dispatcher),
            scope = scope,
        )
    }

    private suspend fun document(vararg edits: PageEdits, width: Int = 1000, height: Int = 1400): DocumentId {
        val pages = edits.map { e ->
            val file = tmp.newFile().apply { writeText("jpeg bytes") }
            StagedPage(StagedImage(file, width, height, "image/jpeg", PageOrigin.SCANNER), e)
        }
        return library.createDocument("Doc", null, pages)
    }

    /** A model that is missing and whose download fails until [installs] is set. */
    private class FlakyModel(private val ocr: FakeOcr) {
        var installs = false
        private var present = false

        init {
            ocr.answer = { image, _ ->
                if (present) OcrOutcome.Recognized("こんにちは", emptyList(), image.widthPx, image.heightPx, 5, "fake/1")
                else OcrOutcome.ModelNotReady(ModuleState.Failed(ModuleFailure.UNKNOWN, retryable = true))
            }
            ocr.models = {
                if (installs) {
                    kotlinx.coroutines.flow.flow {
                        emit(ModuleState.Checking)
                        present = true
                        emit(ModuleState.Ready)
                    }
                } else {
                    flowOf(ModuleState.Checking, ModuleState.Failed(ModuleFailure.UNKNOWN, retryable = true))
                }
            }
        }
    }

    /** Kicks until the model has failed too often and the pages are given up on. */
    private suspend fun TestScope.exhaust(processor: QueuedPageProcessor, doc: DocumentId) {
        repeat(4) {
            processor.awaitIdle()
            runCurrent() // the download runs in the background scope
        }
        assertThat(library.pagesOf(doc).map { it.textStatus }.toSet()).containsExactly(TextStatus.FAILED)
    }

    /** Waits for the pages, then for any model download that started, then for the pages it released. */
    private suspend fun TestScope.settle(processor: QueuedPageProcessor) {
        processor.awaitIdle()
        runCurrent()
        processor.awaitIdle()
    }

    @Test
    fun rendersEditedPagesReusesUneditedSourcesThenReadsText() = runTest {
        val doc = document(PageEdits(), PageEdits(rotation = Rotation.R90, filter = PageFilter.GRAYSCALE))
        val line = TextLine("Invoice 42", BoxPx(1f, 2f, 3f, 4f))
        ocr.answer = { image, _ -> OcrOutcome.Recognized("Invoice 42", listOf(line), image.widthPx, image.heightPx, 5, "fake/1") }
        val processor = processor()

        processor.awaitIdle()

        val (plain, turned) = library.pagesOf(doc)
        assertThat(plain.render).isEqualTo(plain.source)
        assertThat(File(plain.thumbPath!!).name).isEqualTo("thumb-0.jpg")
        assertThat(File(turned.render!!.path).name).isEqualTo("render-0.jpg")
        assertThat(turned.render!!.widthPx to turned.render!!.heightPx).isEqualTo(1400 to 1000)
        assertThat(renderer.rendered).containsExactly(PageEdits(rotation = Rotation.R90, filter = PageFilter.GRAYSCALE))
        assertThat(library.pagesOf(doc).map { it.textStatus }).containsExactly(TextStatus.DONE, TextStatus.DONE)
        val text = library.texts.getValue(turned.id)
        assertThat(text.imageWidthPx to text.imageHeightPx).isEqualTo(1400 to 1000)
        assertThat(text.lines).containsExactly(line)
        assertThat(text.recognizedAt).isEqualTo(T0)
        assertThat(text.engine).isEqualTo("fake/1")
        assertThat(ocr.calls.map { it.first }).containsExactly(plain.source.path, turned.render!!.path)
        assertThat(processor.state.value).isEqualTo(ProcessorState(0, 0, null, null))
    }

    @Test
    fun scriptComesFromOverrideThenSettingThenDeviceLanguages() = runTest {
        locales = listOf(Locale.forLanguageTag("en-IN"), Locale.forLanguageTag("hi-IN"))
        val doc = document(PageEdits())
        val processor = processor()
        processor.awaitIdle()
        assertThat(library.texts.values.single().script).isEqualTo(OcrScript.DEVANAGARI)

        settings.update { it.copy(ocrScript = OcrScript.LATIN) }
        processor.reread(doc, OcrScript.JAPANESE)
        processor.awaitIdle()
        assertThat(library.scriptOverride(doc)).isEqualTo(OcrScript.JAPANESE)
        assertThat(ocr.calls.last().second).isEqualTo(OcrScript.JAPANESE)

        processor.reread(doc, null)
        processor.awaitIdle()
        assertThat(ocr.calls.last().second).isEqualTo(OcrScript.LATIN)
    }

    @Test
    fun autoTextOffSkipsReadingUntilAskedToReadAgain() = runTest {
        settings.state.value = Settings(autoText = false)
        val doc = document(PageEdits())
        val processor = processor()
        processor.awaitIdle()
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.SKIPPED)
        assertThat(ocr.calls).isEmpty()

        processor.reread(doc, null)
        processor.awaitIdle()
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.DONE)
    }

    @Test
    fun anEditDuringTheRenderIsNotLost() = runTest {
        val doc = document(PageEdits(filter = PageFilter.AUTO))
        val page = library.pagesOf(doc).single()
        var edited = false
        renderer.beforeReturn = {
            if (!edited) {
                edited = true
                library.editPage(page.id) { it.copy(rotation = Rotation.R180) }
            }
        }
        processor().awaitIdle()
        val done = library.page(page.id)
        assertThat(done.editsVersion).isEqualTo(1)
        assertThat(File(done.render!!.path).name).isEqualTo("render-1.jpg")
        assertThat(File(done.render!!.path).parentFile!!.list()!!.toList()).doesNotContain("render-0.jpg")
        assertThat(renderer.rendered).hasSize(2)
    }

    @Test
    fun aFailedRenderMarksTheTextFailedAndDoesNotLoop() = runTest {
        renderer.failWith = { _, _ -> IOException("disk full") }
        val doc = document(PageEdits(filter = PageFilter.BLACK_WHITE))
        processor().awaitIdle()
        val page = library.pagesOf(doc).single()
        assertThat(page.render).isNull()
        assertThat(page.textStatus).isEqualTo(TextStatus.FAILED)
        assertThat(page.thumbPath).isNull()
        assertThat(renderer.specs).hasSize(1)
    }

    @Test
    fun retryingAPageWhoseRenderFailedRendersAndReadsItAgain() = runTest {
        renderer.failWith = { _, _ -> IOException("disk full") }
        val doc = document(PageEdits(filter = PageFilter.BLACK_WHITE))
        val processor = processor()
        processor.awaitIdle()
        val failed = library.pagesOf(doc).single()
        assertThat(failed.textStatus).isEqualTo(TextStatus.FAILED)

        // Space was freed; the Text pane's Retry queues the page again.
        renderer.failWith = { _, _ -> null }
        library.setTextStatus(failed.id, failed.editsVersion, TextStatus.PENDING)
        processor.kick()
        processor.awaitIdle()

        val page = library.page(failed.id)
        assertThat(File(page.render!!.path).name).isEqualTo("render-0.jpg")
        assertThat(page.textStatus).isEqualTo(TextStatus.DONE)
    }

    @Test
    fun runningOutOfMemoryFailsThatPageAndTheWorkerCarriesOn() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> uncaught += e })
        val doc = document(PageEdits(filter = PageFilter.AUTO), PageEdits(filter = PageFilter.AUTO))
        val (big, fine) = library.pagesOf(doc)
        renderer.failWith = { source, _ -> OutOfMemoryError("48 MB").takeIf { source.path == big.source.path } }
        val processor = processor(scope)

        processor.awaitIdle()

        assertThat(library.page(big.id).render).isNull()
        assertThat(library.page(big.id).textStatus).isEqualTo(TextStatus.FAILED)
        assertThat(library.page(fine.id).render).isNotNull()
        assertThat(library.page(fine.id).textStatus).isEqualTo(TextStatus.DONE)
        assertThat(library.pagesOf(doc).flatMap { library.pageDir(it.fileKey).list()!!.toList() })
            .containsExactly("source.jpg", "source.jpg", "render-0.jpg", "thumb-0.jpg") // nothing half-made is left
        // The worker is still there for the next page.
        val next = document(PageEdits())
        withTimeout(60_000) { processor.awaitIdle(listOf(next)) }
        assertThat(library.pagesOf(next).single().textStatus).isEqualTo(TextStatus.DONE)
        assertThat(uncaught).isEmpty()
        scope.cancel()
    }

    @Test
    fun aLargePageThatDoesNotFitInMemoryIsRenderedSmaller() = runTest {
        renderer.failWith = { _, spec -> OutOfMemoryError("64 MB").takeIf { spec.maxLongEdgePx == null } }
        val doc = document(PageEdits(filter = PageFilter.GRAYSCALE), width = 4000, height = 3000)
        processor().awaitIdle()
        val page = library.pagesOf(doc).single()
        assertThat(renderer.specs.map { it.maxLongEdgePx }).containsExactly(null, 3000).inOrder()
        assertThat(page.render!!.widthPx to page.render!!.heightPx).isEqualTo(3000 to 2250)
        assertThat(page.textStatus).isEqualTo(TextStatus.DONE)
    }

    @Test
    fun anOutOfMemoryErrorWhileReadingEndsOnlyThatPass() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> uncaught += e })
        var reads = 0
        ocr.answer = { image, _ ->
            if (reads++ == 0) throw OutOfMemoryError("OCR")
            OcrOutcome.Recognized("Invoice 42", emptyList(), image.widthPx, image.heightPx, 5, "fake/1")
        }
        val doc = document(PageEdits())
        val processor = processor(scope)
        processor.awaitIdle()
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.PENDING)

        withTimeout(60_000) { processor.awaitIdle() }
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.DONE)
        assertThat(uncaught).isEmpty()
        scope.cancel()
    }

    @Test
    fun engineFailureMarksThePageFailed() = runTest {
        ocr.answer = { _, _ -> OcrOutcome.Failed("MlKitException:13") }
        val doc = document(PageEdits())
        processor().awaitIdle()
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.FAILED)
    }

    @Test
    fun aMissingModelHoldsPagesWhileItDownloadsThenReadsThem() = runTest {
        var present = false
        ocr.answer = { image, _ ->
            if (present) OcrOutcome.Recognized("こんにちは", emptyList(), image.widthPx, image.heightPx, 5, "fake/1")
            else OcrOutcome.ModelNotReady(ModuleState.Downloading(0, null))
        }
        val gate = CompletableDeferred<Unit>()
        ocr.models = {
            kotlinx.coroutines.flow.flow {
                emit(ModuleState.Downloading(10, 100))
                gate.await()
                present = true
                emit(ModuleState.Ready)
            }
        }
        locales = listOf(Locale.JAPAN)
        val doc = document(PageEdits())
        val processor = processor()
        processor.awaitIdle()
        runCurrent() // the download runs in the background scope
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.PENDING)
        assertThat(processor.state.value.models).isEqualTo(ModuleState.Downloading(10, 100))
        assertThat(ocr.modelRequests).containsExactly(setOf(OcrScript.JAPANESE))

        gate.complete(Unit)
        runCurrent()
        processor.awaitIdle()
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.DONE)
        assertThat(processor.state.value.models).isNull()
    }

    @Test
    fun anOfflineDownloadTriesAgainOnceOnline() = runTest {
        var present = false
        ocr.answer = { image, _ ->
            if (present) OcrOutcome.Recognized("서울", emptyList(), image.widthPx, image.heightPx, 5, "fake/1")
            else OcrOutcome.ModelNotReady(ModuleState.Downloading(0, null))
        }
        var tries = 0
        ocr.models = {
            tries++
            if (tries == 1) {
                flowOf(ModuleState.Checking, ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
            } else {
                present = true
                flowOf(ModuleState.Checking, ModuleState.Ready)
            }
        }
        locales = listOf(Locale.KOREA)
        val doc = document(PageEdits())
        val processor = processor()
        processor.awaitIdle()
        runCurrent()
        assertThat(processor.state.value.models).isEqualTo(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
        assertThat(tries).isEqualTo(1)

        online.complete(Unit)
        runCurrent()
        assertThat(tries).isEqualTo(1) // waits a moment after the network returns
        advanceTimeBy(20_000)
        runCurrent()
        processor.awaitIdle()
        assertThat(tries).isEqualTo(2)
        assertThat(library.pagesOf(doc).single().textStatus).isEqualTo(TextStatus.DONE)
    }

    @Test
    fun withoutPlayServicesPagesFailInsteadOfWaiting() = runTest {
        val unsupported = ModuleState.Unsupported(UnsupportedReason.NO_PLAY_SERVICES)
        ocr.answer = { _, _ -> OcrOutcome.ModelNotReady(unsupported) }
        val doc = document(PageEdits(), PageEdits())
        val processor = processor()
        processor.awaitIdle()
        assertThat(library.pagesOf(doc).map { it.textStatus }).containsExactly(TextStatus.FAILED, TextStatus.FAILED)
        assertThat(processor.state.value.models).isEqualTo(unsupported)
        assertThat(ocr.modelRequests).isEmpty()

        // Play services arrive later: Read again works and the state stops saying it cannot.
        ocr.answer = { image, _ -> OcrOutcome.Recognized("Invoice 42", emptyList(), image.widthPx, image.heightPx, 5, "fake/1") }
        processor.reread(doc, null)
        processor.awaitIdle()
        assertThat(library.pagesOf(doc).map { it.textStatus }).containsExactly(TextStatus.DONE, TextStatus.DONE)
        assertThat(processor.state.value.models).isNull()
    }

    @Test
    fun readAgainGivesAModelThatKeptFailingFreshAttempts() = runTest {
        locales = listOf(Locale.JAPAN)
        val model = FlakyModel(ocr)
        val doc = document(PageEdits(), PageEdits())
        val processor = processor()
        exhaust(processor, doc)

        model.installs = true
        processor.reread(doc, null)
        settle(processor)

        assertThat(library.pagesOf(doc).map { it.textStatus }).containsExactly(TextStatus.DONE, TextStatus.DONE)
        assertThat(processor.state.value.models).isNull()
    }

    @Test
    fun retryGivesAModelThatKeptFailingFreshAttempts() = runTest {
        locales = listOf(Locale.JAPAN)
        val model = FlakyModel(ocr)
        val doc = document(PageEdits(), PageEdits())
        val processor = processor()
        exhaust(processor, doc)

        // A kick alone does not bring the pages back, and a page queued again fails straight away...
        val (first, second) = library.pagesOf(doc)
        library.setTextStatus(first.id, first.editsVersion, TextStatus.PENDING)
        processor.kick()
        processor.awaitIdle()
        runCurrent()
        assertThat(library.page(first.id).textStatus).isEqualTo(TextStatus.FAILED)

        // ...until Retry gives the model new attempts.
        model.installs = true
        library.setTextStatus(first.id, first.editsVersion, TextStatus.PENDING)
        processor.retry()
        settle(processor)

        assertThat(library.page(first.id).textStatus).isEqualTo(TextStatus.DONE)
        assertThat(library.page(second.id).textStatus).isEqualTo(TextStatus.FAILED)
    }

    @Test
    fun awaitIdleForOneDocumentReturnsWhenThatDocumentIsDone() = runTest {
        val first = document(PageEdits())
        val processor = processor()
        processor.awaitIdle(listOf(first))
        assertThat(library.pagesOf(first).single().textStatus).isEqualTo(TextStatus.DONE)
        processor.awaitIdle(listOf(DocumentId(999)))
    }
}
