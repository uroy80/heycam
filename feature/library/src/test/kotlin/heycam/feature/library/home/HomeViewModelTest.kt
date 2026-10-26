package heycam.feature.library.home

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.SortOrder
import heycam.feature.library.FakeLibrary
import heycam.feature.library.FakePageProcessor
import heycam.feature.library.MainDispatcherRule
import heycam.feature.library.contents.LibraryMessage
import heycam.feature.library.folder
import heycam.feature.library.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = FakeLibrary(
        docs = listOf(
            summary(1, "Old lease", updated = 1),
            summary(2, "Passport copy", updated = 2, pinned = true),
            summary(3, "Bank letter", folder = 10, updated = 3),
            summary(4, "Receipt", updated = 4),
            summary(5, "Deleted thing", updated = 9, trashed = true),
        ),
        folders = listOf(folder(10, "Bank", count = 1)),
    )
    private val processor = FakePageProcessor()

    private fun TestScope.viewModel(handle: SavedStateHandle = SavedStateHandle()): HomeViewModel {
        val vm = HomeViewModel(library, library, processor, handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun emptyLibraryShowsTheFirstRunState() = runTest {
        library.docs.value = emptyList()
        library.folders.value = emptyList()
        val state = viewModel().state.value
        assertThat(state.loaded).isTrue()
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun latestLeadsThenPinnedThenFoldersThenTheRest() = runTest {
        val state = viewModel().state.value
        assertThat(state.latest!!.title).isEqualTo("Receipt")
        assertThat(state.pinned.map { it.title }).containsExactly("Passport copy")
        assertThat(state.sections.single().folder.name).isEqualTo("Bank")
        assertThat(state.sections.single().preview.map { it.title }).containsExactly("Bank letter")
        assertThat(state.documents.map { it.title }).containsExactly("Old lease")
        assertThat(state.stats.documents).isEqualTo(4)
        assertThat(state.stats.pages).isEqualTo(8)
        assertThat(state.isEmpty).isFalse()
    }

    @Test
    fun latestInAFolderNamesIt() = runTest {
        library.docs.value = library.docs.value + summary(6, "Statement", folder = 10, updated = 8)
        val state = viewModel().state.value
        assertThat(state.latest!!.title).isEqualTo("Statement")
        assertThat(state.latestFolder).isEqualTo("Bank")
        assertThat(state.sections.single().preview.map { it.title }).containsExactly("Bank letter")
    }

    @Test
    fun sortIsRememberedAcrossProcessDeath() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.setSort(SortOrder.TITLE_ASC)
        assertThat(vm.state.value.sort).isEqualTo(SortOrder.TITLE_ASC)
        assertThat(viewModel(SavedStateHandle(mapOf("sort" to handle.get<SortOrder>("sort")))).state.value.sort)
            .isEqualTo(SortOrder.TITLE_ASC)
    }

    @Test
    fun trashOffersUndo() = runTest {
        val vm = viewModel()
        vm.toggleSelection(DocumentId(1))
        vm.toggleSelection(DocumentId(4))
        assertThat(vm.state.value.selected.map { it.title }).containsExactly("Receipt", "Old lease")
        vm.messages.test {
            vm.trash(vm.state.value.selection.toList())
            val message = awaitItem() as LibraryMessage.Trashed
            assertThat(message.ids).containsExactly(DocumentId(1), DocumentId(4))
            vm.undoTrash(message.ids)
        }
        assertThat(vm.selection.value).isEmpty()
        assertThat(library.docs.value.filter { it.trashedAt == null }.map { it.id.value }).containsExactly(1L, 2L, 3L, 4L)
        // Trashed documents are skipped by the page processor, so Undo wakes it for any unfinished pages.
        assertThat(processor.kicks).isEqualTo(1)
    }

    @Test
    fun selectionDropsDocumentsThatLeave() = runTest {
        val vm = viewModel()
        vm.toggleSelection(DocumentId(1))
        vm.toggleSelection(DocumentId(2))
        library.docs.value = library.docs.value.filter { it.id != DocumentId(1) }
        assertThat(vm.state.value.selection).containsExactly(DocumentId(2))
    }

    @Test
    fun pinPinsAllUnlessAllArePinned() = runTest {
        val vm = viewModel()
        val mixed = library.docs.value.filter { it.id.value in setOf(1L, 2L) }
        vm.togglePinned(mixed)
        assertThat(library.docs.value.filter { it.pinned }.map { it.id.value }).containsExactly(1L, 2L)
        vm.togglePinned(library.docs.value.filter { it.id.value in setOf(1L, 2L) })
        assertThat(library.docs.value.filter { it.pinned }).isEmpty()
    }

    @Test
    fun moveReportsTheFolder() = runTest {
        val vm = viewModel()
        vm.messages.test {
            vm.moveTo(listOf(DocumentId(4)), library.folders.value.single())
            assertThat(awaitItem()).isEqualTo(LibraryMessage.Moved(1, "Bank"))
        }
        assertThat(vm.state.value.sections.single().preview.map { it.title }).containsExactly("Bank letter")
        assertThat(vm.state.value.latest!!.title).isEqualTo("Receipt")
        assertThat(vm.state.value.latestFolder).isEqualTo("Bank")
    }

    @Test
    fun foldersStopAtThreeLevels() = runTest {
        val vm = viewModel()
        vm.messages.test {
            vm.createFolder("Too deep", parent = null, parentDepth = 3)
            assertThat(awaitItem()).isEqualTo(LibraryMessage.FolderLimit)
        }
        vm.createFolder("  Taxes ", parent = null, parentDepth = 0)
        assertThat(library.folders.value.map { it.name }).contains("Taxes")
    }

    @Test
    fun folderNamesAreUniqueIgnoringCase() = runTest {
        val vm = viewModel()
        assertThat(vm.folderNames(parent = null)).containsExactly("bank")
        vm.messages.test {
            vm.createFolder(" bank ", parent = null, parentDepth = 0)
            assertThat(awaitItem()).isEqualTo(LibraryMessage.NameInUse)
            vm.createFolder("Bank", parent = null, parentDepth = 0)
            assertThat(awaitItem()).isEqualTo(LibraryMessage.NameInUse)
        }
        assertThat(library.folders.value.map { it.name }).containsExactly("Bank")
        // The same name is fine inside another folder.
        vm.createFolder("Bank", parent = FolderId(10), parentDepth = 1)
        assertThat(library.folders.value.map { it.name }).containsExactly("Bank", "Bank")
    }

    @Test
    fun folderTreeIsDepthFirstByName() = runTest {
        library.folders.value = listOf(folder(1, "Work"), folder(2, "Bank"), folder(3, "Loans", parent = 2), folder(4, "Car", parent = 3))
        val tree = viewModel().folderTree()
        assertThat(tree.map { it.folder.name to it.depth }).containsExactly("Bank" to 0, "Loans" to 1, "Car" to 2, "Work" to 0).inOrder()
    }

    @Test
    fun failedWriteSaysSo() = runTest {
        library.failWrites = true
        val vm = viewModel()
        vm.messages.test {
            vm.rename(DocumentId(1), "New")
            assertThat(awaitItem()).isEqualTo(LibraryMessage.Failed)
        }
    }
}
