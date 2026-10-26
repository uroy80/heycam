package heycam.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.data.StorageUsage
import heycam.core.model.DocumentId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class StorageViewModelTest {
    private val files = FakeFileStore(StorageUsage(pagesBytes = 50_000, trashBytes = 4_000, cacheBytes = 900))
    private val documents = FakeDocumentRepository(trashed(7), trashed(8))
    private val log = RecordingLog()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): StorageViewModel {
        val vm = StorageViewModel(files, documents, log)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun showsUsageAndTrash() = runTest {
        val state = viewModel().uiState.value
        assertThat(state.usage).isEqualTo(StorageUsage(50_000, 4_000, 900))
        assertThat(state.trashCount).isEqualTo(2)
        assertThat(state.busy).isFalse()
    }

    @Test
    fun emptyingTrashDeletesEveryTrashedDocument() = runTest {
        val vm = viewModel()
        vm.messages.test {
            vm.emptyTrash()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(StorageMessage.TrashEmptied)
        }
        assertThat(documents.deleted).containsExactly(listOf(DocumentId(7), DocumentId(8)))
        assertThat(vm.uiState.value.trashCount).isEqualTo(0)
    }

    @Test
    fun clearingTemporaryFilesReportsWhatWasFreed() = runTest {
        files.freedBytes = 900
        val vm = viewModel()
        vm.messages.test {
            vm.clearTemporaryFiles()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(StorageMessage.Freed(900))
        }
        assertThat(vm.uiState.value.usage?.cacheBytes).isEqualTo(0)
    }

    @Test
    fun aFailureIsShortAndLogsNoContent() = runTest {
        files.clearFailure = IOException("/data/user/0/in.edgr.heycam/cache/exports/Lease.pdf")
        val vm = viewModel()
        vm.messages.test {
            vm.clearTemporaryFiles()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(StorageMessage.Failed)
        }
        assertThat(vm.uiState.value.busy).isFalse()
        assertThat(log.lines).containsExactly("W/Storage storage action failed: IOException")
    }

    @Test
    fun oneActionAtATime() = runTest {
        val vm = viewModel()
        vm.clearTemporaryFiles()
        vm.clearTemporaryFiles()
        vm.emptyTrash()
        advanceUntilIdle()
        assertThat(files.clears).isEqualTo(1)
        assertThat(documents.deleted).isEmpty()
    }
}
