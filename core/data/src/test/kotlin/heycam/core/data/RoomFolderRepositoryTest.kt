package heycam.core.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import heycam.core.model.FolderId
import heycam.core.model.SortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomFolderRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var rig: DataTestRig
    private val folders get() = rig.folders

    @Before fun setUp() { rig = DataTestRig(temp.root) }
    @After fun tearDown() = rig.close()

    @Test
    fun nestsThreeDeepAtMost() = runTest {
        val one = folders.create("One", null)
        val two = folders.create("Two", one)
        val three = folders.create("Three", two)
        assertThat(runCatching { folders.create("Four", three) }.exceptionOrNull()).isInstanceOf(FolderTooDeepException::class.java)
        assertThat(runCatching { folders.create("Lost", FolderId(999)) }.exceptionOrNull()).isInstanceOf(NoSuchElementException::class.java)
        assertThat(runCatching { folders.create(" \t", null) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(folders.observeFolders(two).first().map { it.id }).containsExactly(three)
    }

    @Test
    fun foldersAreSortedByNameAndCountLiveDocuments() = runTest {
        val bills = folders.create("bills", null)
        folders.create("Archive", null)
        folders.create("Écoles", null)
        folders.create("zoo", null)
        rig.documents.createDocument("Kept", bills, listOf(rig.staged()))
        val binned = rig.documents.createDocument("Binned", bills, listOf(rig.staged()))
        rig.documents.trash(listOf(binned))
        val top = folders.observeFolders(null).first()
        assertThat(top.map { it.name }).containsExactly("Archive", "bills", "Écoles", "zoo").inOrder()
        assertThat(top.first { it.id == bills }.documentCount).isEqualTo(1)
        assertThat(top.first { it.id == bills }.createdAt).isEqualTo(rig.clock.now)
    }

    @Test
    fun foldersSideBySideCannotShareANameIgnoringCase() = runTest {
        val bank = folders.create("Bank", null)
        val taxes = folders.create("Taxes", null)
        assertThat(runCatching { folders.create("  bank ", null) }.exceptionOrNull()).isInstanceOf(FolderNameInUseException::class.java)
        assertThat(runCatching { folders.rename(taxes, "BANK") }.exceptionOrNull()).isInstanceOf(FolderNameInUseException::class.java)
        // The same name is fine one level down, and a folder may change its own name's case.
        folders.create("Bank", bank)
        folders.rename(bank, "BANK")
        assertThat(folders.observeFolders(null).first().map { it.name }).containsExactly("BANK", "Taxes").inOrder()
    }

    @Test
    fun renameCleansTheName() = runTest {
        val id = folders.create("Old", null)
        folders.observeFolders(null).test {
            assertThat(awaitItem().single().name).isEqualTo("Old")
            folders.rename(id, "  New\tname  ")
            assertThat(awaitItem().single().name).isEqualTo("New name")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteMovesDocumentsAndLiftsSubFolders() = runTest {
        val parent = folders.create("Parent", null)
        val doomed = folders.create("Doomed", parent)
        val child = folders.create("Child", doomed)
        val target = folders.create("Target", null)
        val doc = rig.documents.createDocument("Doc", doomed, listOf(rig.staged()))
        val other = rig.documents.createDocument("Other", doomed, listOf(rig.staged()))
        rig.documents.trash(listOf(other))

        folders.delete(doomed, moveDocumentsTo = target)
        assertThat(folders.observeFolders(parent).first().map { it.id }).containsExactly(child)
        assertThat(rig.documents.observeDocument(doc).first()!!.folderId).isEqualTo(target)
        assertThat(rig.documents.observeDocument(other).first()!!.folderId).isEqualTo(target)
        assertThat(rig.documents.observeLibrary(target, SortOrder.TITLE_ASC).first().documents.map { it.id }).containsExactly(doc)

        folders.delete(target, moveDocumentsTo = target)
        assertThat(rig.documents.observeDocument(doc).first()!!.folderId).isNull()
        folders.delete(FolderId(999), null)
    }
}
