package heycam.feature.document

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.export.ExportFailure
import heycam.core.export.ExportResult
import heycam.core.export.ExportTarget
import heycam.core.export.TextPendingPolicy
import heycam.core.model.DocumentId
import heycam.core.model.ExportFormat
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.Rotation
import heycam.core.model.TextStatus
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DocumentViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeDocumentRepository(document(pages = (0..3).map { page(it + 1L, it) }))
    private val folders = FakeFolderRepository(listOf(Folder(FolderId(1), "Home", null, Instant.EPOCH, 0)))
    private val processor = FakePageProcessor()
    private val workStore = FakePageWorkStore()
    private val export = FakeExportService()

    init {
        repo.texts.value = mapOf(
            DocumentId(7) to listOf(
                pageText(1, "The tenant will pay a deposit."),
                pageText(2, "The deposit is held."),
                pageText(3, "Rent is due monthly."),
            ),
        )
    }

    private fun TestScope.viewModel(vararg args: Pair<String, Any?>): DocumentViewModel {
        val vm = DocumentViewModel(
            repo, folders, processor, workStore, repo, export, PageDeletions(repo, backgroundScope),
            SavedStateHandle(mapOf<String, Any?>("documentId" to 7L) + args),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.find.collect {} }
        return vm
    }

    @Test
    fun opensOnPagesByDefault() = runTest {
        val vm = viewModel()
        assertThat(vm.tab.value).isEqualTo(DocumentTab.PAGES)
        assertThat(vm.initialPage).isNull()
        val state = vm.state.value
        assertThat(state.pages.map { it.id.value }).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(state.storedBytes).isEqualTo(4_000_000L)
        assertThat(state.textReady).isTrue()
    }

    @Test
    fun textFlagNeedsSomeText() = runTest {
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0, status = TextStatus.EMPTY), page(2, 1, status = TextStatus.EMPTY))))
        val vm = viewModel()
        // A photo or a blank page: read, but nothing found, so no TEXT flag.
        assertThat(vm.state.value.textReady).isFalse()
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0, status = TextStatus.DONE), page(2, 1, status = TextStatus.EMPTY))))
        assertThat(vm.state.value.textReady).isTrue()
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0, status = TextStatus.DONE), page(2, 1, status = TextStatus.PENDING))))
        assertThat(vm.state.value.textReady).isFalse()
    }

    @Test
    fun searchHitOpensTheTextWithTheQuery() = runTest {
        val vm = viewModel("pageIndex" to 1, "showText" to true, "highlight" to "deposit")
        assertThat(vm.tab.value).isEqualTo(DocumentTab.TEXT)
        assertThat(vm.initialPage).isEqualTo(1)
        val find = vm.find.value
        assertThat(find.query).isEqualTo("deposit")
        assertThat(find.matches.map { it.pageIndex }).containsExactly(0, 1).inOrder()
        // Opened from the hit on page 2, so that match is current.
        assertThat(find.currentMatch!!.pageIndex).isEqualTo(1)
        vm.nextMatch()
        assertThat(vm.find.value.current).isEqualTo(0)
        vm.nextMatch()
        assertThat(vm.find.value.current).isEqualTo(1)
        vm.previousMatch()
        assertThat(vm.find.value.current).isEqualTo(0)
        vm.setQuery("rent")
        assertThat(vm.find.value.matches.single().pageIndex).isEqualTo(2)
        assertThat(vm.find.value.current).isEqualTo(0)
    }

    @Test
    fun moveUsesStoredIndicesEvenWithAPageAwaitingDelete() = runTest {
        val vm = viewModel()
        vm.deletePage(PageId(2))
        assertThat(vm.state.value.pages.map { it.id.value }).containsExactly(1L, 3L, 4L).inOrder()
        vm.movePage(from = 2, to = 0) // page 4 to the front
        assertThat(repo.moves.single()).isEqualTo(Triple(DocumentId(7), 3, 0))
        assertThat(vm.state.value.pages.map { it.id.value }).containsExactly(4L, 1L, 3L).inOrder()
    }

    @Test
    fun failedMoveRestoresTheOrder() = runTest {
        val vm = viewModel()
        repo.failWrites = true
        vm.events.test {
            vm.movePage(0, 3)
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.Failed))
        }
        assertThat(vm.state.value.pages.map { it.id.value }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun deletedPageGoesAfterTheUndoWindow() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.deletePage(PageId(3))
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.PageDeleted(PageId(3))))
        }
        assertThat(vm.state.value.pages).hasSize(3)
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS + 1)
        runCurrent()
        assertThat(repo.deleted.single()).containsExactly(PageId(3))
    }

    @Test
    fun undoBringsThePageBack() = runTest {
        val vm = viewModel()
        vm.deletePage(PageId(3))
        vm.undoDelete(PageId(3))
        assertThat(vm.state.value.pages).hasSize(4)
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS * 2)
        assertThat(repo.deleted).isEmpty()
    }

    @Test
    fun theOnlyPageIsNeverDeleted() = runTest {
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0))))
        val vm = viewModel()
        vm.deletePage(PageId(1))
        assertThat(vm.state.value.pages).hasSize(1)
    }

    @Test
    fun rotateAndFilterEditThenWakeTheProcessor() = runTest {
        val vm = viewModel()
        vm.rotate(PageId(2))
        vm.setFilter(listOf(PageId(1), PageId(2)), PageFilter.BLACK_WHITE)
        val pages = vm.state.value.pages
        assertThat(pages[1].edits.rotation).isEqualTo(Rotation.R90)
        assertThat(pages[0].edits.filter).isEqualTo(PageFilter.BLACK_WHITE)
        assertThat(processor.kicks).isEqualTo(2)
    }

    @Test
    fun correctionsAreSavedAsNfc() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.saveText(PageId(1), "  cafe\u0301 deposit ")
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.TextSaved))
        }
        assertThat(vm.state.value.texts.getValue(PageId(1)).text).isEqualTo("caf\u00e9 deposit")
        // Corrections go through the text editor, never the page processor's work store.
        assertThat(workStore.savedTexts).isEmpty()
    }

    @Test
    fun correctionAfterAFilterChangeIsSaved() = runTest {
        val vm = viewModel()
        // A filter-only edit bumps the page's edits version but keeps its text.
        vm.setFilter(listOf(PageId(2)), PageFilter.GRAYSCALE)
        assertThat(vm.state.value.pages[1].editsVersion).isEqualTo(2)
        vm.events.test {
            vm.saveText(PageId(2), "The deposit is fixed.")
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.TextSaved))
        }
        assertThat(vm.state.value.texts.getValue(PageId(2)).text).isEqualTo("The deposit is fixed.")
    }

    @Test
    fun correctionOfAPageThatIsGoneFails() = runTest {
        val vm = viewModel()
        repo.texts.value = mapOf(DocumentId(7) to repo.texts.value.getValue(DocumentId(7)) + pageText(9, "Orphan"))
        vm.events.test {
            vm.saveText(PageId(9), "Changed")
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.Failed))
        }
    }

    @Test
    fun unchangedCorrectionIsNotSaved() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.saveText(PageId(3), "Rent is due monthly.")
            expectNoEvents()
        }
    }

    @Test
    fun deleteUsesTheGivenUndoWindow() = runTest {
        val vm = viewModel()
        vm.deletePage(PageId(3), windowMs = 20_000)
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS + 1)
        runCurrent()
        assertThat(repo.deleted).isEmpty()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(repo.deleted.single()).containsExactly(PageId(3))
    }

    @Test
    fun shareTextAsksWhileTextIsPendingThenSharesWithoutIt() = runTest {
        export.results += ExportResult.TextPending(2)
        export.results += ExportResult.ReadyToShare(Intent(Intent.ACTION_SEND))
        val vm = viewModel()
        vm.events.test {
            vm.shareText()
            assertThat(awaitItem()).isEqualTo(DocumentEvent.AskTextPending(2))
            vm.shareText(TextPendingPolicy.WITHOUT_TEXT)
            assertThat(awaitItem()).isInstanceOf(DocumentEvent.Share::class.java)
        }
        val (request, target) = export.requests.last()
        assertThat(request.options.format).isEqualTo(ExportFormat.TEXT)
        assertThat(request.whenTextPending).isEqualTo(TextPendingPolicy.WITHOUT_TEXT)
        assertThat(target).isEqualTo(ExportTarget.Share)
        assertThat(vm.state.value.sharing).isFalse()
    }

    @Test
    fun shareFailureIsShortAndCancelIsSilent() = runTest {
        export.results += ExportResult.Failed(ExportFailure.NO_SPACE)
        export.results += ExportResult.Failed(ExportFailure.CANCELLED)
        val vm = viewModel()
        vm.events.test {
            vm.shareText()
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.ExportFailed(ExportFailure.NO_SPACE)))
            vm.shareText()
            expectNoEvents()
        }
    }

    @Test
    fun readAgainAndRetry() = runTest {
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0), page(2, 1, status = TextStatus.FAILED))))
        val vm = viewModel()
        vm.readAgain(OcrScript.DEVANAGARI)
        assertThat(processor.rereads.single()).isEqualTo(DocumentId(7) to OcrScript.DEVANAGARI)
        vm.retryText(PageId(2))
        assertThat(workStore.statuses.single()).isEqualTo(Triple(PageId(2), 1, TextStatus.PENDING))
        // Retry gives failing text models fresh download attempts, not just another pass.
        assertThat(processor.retries).isEqualTo(1)
        vm.retryModels()
        assertThat(processor.retries).isEqualTo(2)
    }

    @Test
    fun sharingTextThatWaitsWakesTheProcessorFirst() = runTest {
        export.results += ExportResult.ReadyToShare(Intent(Intent.ACTION_SEND))
        val vm = viewModel()
        val before = processor.kicks
        vm.events.test {
            vm.shareText(TextPendingPolicy.WAIT)
            assertThat(awaitItem()).isInstanceOf(DocumentEvent.Share::class.java)
        }
        assertThat(processor.kicks).isEqualTo(before + 1)
        assertThat(export.requests.last().first.whenTextPending).isEqualTo(TextPendingPolicy.WAIT)
    }

    @Test
    fun renamePinMoveAndTrash() = runTest {
        val vm = viewModel()
        vm.rename("  Lease, flat 4B ")
        vm.togglePinned()
        assertThat(vm.state.value.document!!.title).isEqualTo("Lease, flat 4B")
        assertThat(vm.state.value.document!!.pinned).isTrue()
        assertThat(vm.folderTree().map { it.folder.name }).containsExactly("Home")
        vm.events.test {
            vm.moveTo(vm.folderTree().single().folder)
            assertThat(awaitItem()).isEqualTo(DocumentEvent.Message(DocumentMessage.Moved("Home")))
        }
        assertThat(vm.state.value.document!!.folderId).isEqualTo(FolderId(1))
        vm.trash()
        assertThat(vm.state.value.missing).isTrue()
    }

    @Test
    fun modelBannerOnlyWhilePagesWait() = runTest {
        processor.mutableState.value = processor.mutableState.value.copy(models = heycam.core.model.ModuleState.Downloading(1, 10))
        val vm = viewModel()
        assertThat(vm.state.value.models).isNull()
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0, status = TextStatus.PENDING))))
        assertThat(vm.state.value.models).isEqualTo(heycam.core.model.ModuleState.Downloading(1, 10))
        assertThat(vm.state.value.pagesReading).isEqualTo(1)
    }
}
