package heycam.feature.library.folder

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.model.FolderId
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FolderViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = FakeLibrary(
        docs = listOf(
            summary(1, "Statement May", folder = 3, updated = 5),
            summary(2, "Statement April", folder = 3, updated = 4, pinned = true),
            summary(3, "Elsewhere", folder = 1),
        ),
        folders = listOf(folder(1, "Money"), folder(2, "Bank", parent = 1), folder(3, "Statements", parent = 2), folder(4, "2025", parent = 3)),
    )

    private fun TestScope.viewModel(id: Long): FolderViewModel {
        val vm = FolderViewModel(library, library, FakePageProcessor(), SavedStateHandle(mapOf("folderId" to id)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun showsPathSubfoldersAndPinnedFirst() = runTest {
        val state = viewModel(3).state.value
        assertThat(state.folder!!.name).isEqualTo("Statements")
        assertThat(state.path.map { it.name }).containsExactly("Money", "Bank").inOrder()
        assertThat(state.subfolders.map { it.name }).containsExactly("2025")
        assertThat(state.documents.map { it.title }).containsExactly("Statement April", "Statement May").inOrder()
        assertThat(state.depth).isEqualTo(3)
        assertThat(state.canNest).isFalse()
    }

    @Test
    fun topLevelFolderCanNest() = runTest {
        assertThat(viewModel(1).state.value.canNest).isTrue()
    }

    @Test
    fun missingFolderIsReported() = runTest {
        val state = viewModel(99).state.value
        assertThat(state.missing).isTrue()
    }

    @Test
    fun deleteMovesDocumentsUpALevel() = runTest {
        val vm = viewModel(3)
        vm.deleteFolder()
        assertThat(library.deletedFolders.single()).isEqualTo(FolderId(3) to FolderId(2))
        assertThat(vm.state.value.missing).isTrue()
        assertThat(library.docs.value.filter { it.folderId == FolderId(2) }.map { it.title }).containsExactly("Statement May", "Statement April")
    }

    @Test
    fun renameTrims() = runTest {
        val vm = viewModel(2)
        vm.renameFolder("  Banking ")
        assertThat(vm.state.value.folder!!.name).isEqualTo("Banking")
        vm.renameFolder("   ")
        assertThat(vm.state.value.folder!!.name).isEqualTo("Banking")
    }

    @Test
    fun renameRefusesASiblingsNameButAllowsANewCase() = runTest {
        library.folders.value = library.folders.value + folder(5, "Cards", parent = 1)
        val vm = viewModel(2)
        assertThat(vm.siblingNames()).containsExactly("cards")
        vm.messages.test {
            vm.renameFolder("CARDS")
            assertThat(awaitItem()).isEqualTo(LibraryMessage.NameInUse)
        }
        assertThat(vm.state.value.folder!!.name).isEqualTo("Bank")
        vm.renameFolder("BANK")
        assertThat(vm.state.value.folder!!.name).isEqualTo("BANK")
    }

    @Test
    fun subfolderNamesAreUniqueInsideTheFolder() = runTest {
        val vm = viewModel(1)
        assertThat(vm.subfolderNames()).containsExactly("bank")
        vm.messages.test {
            vm.createSubfolder("bank")
            assertThat(awaitItem()).isEqualTo(LibraryMessage.NameInUse)
        }
        assertThat(library.folders.value.count { it.parentId == FolderId(1) }).isEqualTo(1)
    }

    @Test
    fun subfolderAtTheLimitIsRefused() = runTest {
        val vm = viewModel(3)
        vm.messages.test {
            vm.createSubfolder("Deeper")
            assertThat(awaitItem()).isEqualTo(LibraryMessage.FolderLimit)
        }
        val shallow = viewModel(1)
        shallow.createSubfolder("Cards")
        assertThat(library.folders.value.single { it.name == "Cards" }.parentId).isEqualTo(FolderId(1))
    }
}
