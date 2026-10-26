package heycam.feature.library.trash

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.feature.library.FakeLibrary
import heycam.feature.library.FakePageProcessor
import heycam.feature.library.MainDispatcherRule
import heycam.feature.library.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = FakeLibrary(
        docs = listOf(
            summary(1, "Kept"),
            summary(2, "Old receipt", trashed = true),
            summary(3, "Old letter", trashed = true),
        ),
    )

    private val processor = FakePageProcessor()

    private fun TestScope.viewModel(): TrashViewModel {
        val vm = TrashViewModel(library, processor)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun listsOnlyTrashedDocuments() = runTest {
        assertThat(viewModel().state.value.documents.map { it.title }).containsExactly("Old receipt", "Old letter")
    }

    @Test
    fun restoreBringsDocumentsBack() = runTest {
        val vm = viewModel()
        vm.toggleSelection(DocumentId(2))
        vm.messages.test {
            vm.restore(vm.state.value.selection.toList())
            assertThat(awaitItem()).isEqualTo(TrashMessage.Restored(1))
        }
        assertThat(vm.state.value.documents.map { it.title }).containsExactly("Old letter")
        assertThat(vm.state.value.selection).isEmpty()
        // Trashed documents are skipped by the page processor, so a restore wakes it for any unfinished pages.
        assertThat(processor.kicks).isEqualTo(1)
    }

    @Test
    fun emptyDeletesEverythingInTrash() = runTest {
        val vm = viewModel()
        vm.messages.test {
            vm.emptyTrash()
            assertThat(awaitItem()).isEqualTo(TrashMessage.Deleted(2))
        }
        assertThat(library.docs.value.map { it.title }).containsExactly("Kept")
        assertThat(vm.state.value.documents).isEmpty()
    }

    @Test
    fun failureIsReported() = runTest {
        library.failWrites = true
        val vm = viewModel()
        vm.messages.test {
            vm.deleteForever(listOf(DocumentId(2)))
            assertThat(awaitItem()).isEqualTo(TrashMessage.Failed)
        }
        assertThat(vm.state.value.documents).hasSize(2)
    }
}
