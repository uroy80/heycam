package heycam.feature.document.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.Rotation
import heycam.feature.document.DocumentMessage
import heycam.feature.document.FakeDocumentRepository
import heycam.feature.document.FakePageProcessor
import heycam.feature.document.FakePageRenderer
import heycam.feature.document.MainDispatcherRule
import heycam.feature.document.PageDeletions
import heycam.feature.document.document
import heycam.feature.document.page
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
class PageViewerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeDocumentRepository(document(pages = (0..2).map { page(it + 1L, it) }))
    private val renderer = FakePageRenderer()
    private val processor = FakePageProcessor()

    private fun TestScope.viewModel(pageIndex: Int = 1): PageViewerViewModel {
        val vm = PageViewerViewModel(
            repo, renderer, processor, PageDeletions(repo, backgroundScope),
            SavedStateHandle(mapOf("documentId" to 7L, "pageIndex" to pageIndex)),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun opensOnTheChosenPage() = runTest {
        val vm = viewModel(pageIndex = 2)
        assertThat(vm.state.value.current).isEqualTo(2)
        assertThat(vm.state.value.currentPage!!.id).isEqualTo(PageId(3))
        vm.select(0)
        assertThat(vm.state.value.currentPage!!.id).isEqualTo(PageId(1))
        vm.select(9)
        assertThat(vm.state.value.current).isEqualTo(0)
    }

    @Test
    fun rotateAndFilterTheCurrentPage() = runTest {
        val vm = viewModel()
        vm.rotate()
        vm.setFilter(PageFilter.GRAYSCALE)
        val page = vm.state.value.currentPage!!
        assertThat(page.edits.rotation).isEqualTo(Rotation.R90)
        assertThat(page.edits.filter).isEqualTo(PageFilter.GRAYSCALE)
        assertThat(repo.edited).containsExactly(listOf(PageId(2)), listOf(PageId(2)))
        vm.applyFilterToAll(PageFilter.AUTO)
        assertThat(vm.state.value.pages.map { it.edits.filter }).containsExactly(PageFilter.AUTO, PageFilter.AUTO, PageFilter.AUTO)
        assertThat(processor.kicks).isEqualTo(3)
    }

    @Test
    fun deleteHidesThePageThenDeletesIt() = runTest {
        val vm = viewModel()
        vm.messages.test {
            vm.deleteCurrent()
            assertThat(awaitItem()).isEqualTo(DocumentMessage.PageDeleted(PageId(2)))
        }
        assertThat(vm.state.value.pages.map { it.id }).containsExactly(PageId(1), PageId(3))
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS + 1)
        runCurrent()
        assertThat(repo.deleted.single()).containsExactly(PageId(2))
    }

    @Test
    fun undoReturnsToTheRestoredPage() = runTest {
        val vm = viewModel(pageIndex = 2)
        vm.deleteCurrent()
        // The pager settles on the page before, as it does when the last page goes.
        vm.select(1)
        assertThat(vm.state.value.current).isEqualTo(1)
        vm.undoDelete(PageId(3))
        assertThat(vm.state.value.pages).hasSize(3)
        assertThat(vm.state.value.current).isEqualTo(2)
        assertThat(vm.state.value.currentPage!!.id).isEqualTo(PageId(3))
    }

    @Test
    fun undoAfterTheWindowChangesNothing() = runTest {
        val vm = viewModel(pageIndex = 2)
        vm.deleteCurrent()
        vm.select(0)
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS + 1)
        runCurrent()
        vm.undoDelete(PageId(3))
        assertThat(vm.state.value.current).isEqualTo(0)
        assertThat(vm.state.value.pages.map { it.id }).containsExactly(PageId(1), PageId(2))
    }

    @Test
    fun lastPageCannotBeDeleted() = runTest {
        repo.documents.value = mapOf(DocumentId(7) to document(pages = listOf(page(1, 0))))
        val vm = viewModel(pageIndex = 0)
        assertThat(vm.state.value.canDelete).isFalse()
        vm.deleteCurrent()
        assertThat(vm.state.value.pages).hasSize(1)
    }

    @Test
    fun goneDocumentCloses() = runTest {
        val vm = viewModel()
        repo.documents.value = emptyMap()
        assertThat(vm.state.value.missing).isTrue()
    }

    @Test
    fun previewsAreCachedPerEdits() = runTest {
        val vm = viewModel()
        val page = vm.state.value.currentPage!!
        vm.preview(page, page.edits, 400)
        vm.preview(page, page.edits, 400)
        vm.preview(page, page.edits.copy(filter = PageFilter.BLACK_WHITE), 400)
        assertThat(renderer.previews).isEqualTo(2)
    }
}
