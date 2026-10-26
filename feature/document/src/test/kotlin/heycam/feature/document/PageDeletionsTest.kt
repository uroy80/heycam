package heycam.feature.document

import com.google.common.truth.Truth.assertThat
import heycam.core.model.PageId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageDeletionsTest {
    private val repo = FakeDocumentRepository(document(pages = listOf(page(1, 0), page(2, 1))))

    @Test
    fun deletesAfterTheUndoWindow() = runTest {
        val deletions = PageDeletions(repo, backgroundScope)
        deletions.delete(PageId(2))
        assertThat(deletions.pending.value).containsExactly(PageId(2))
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS - 1)
        assertThat(repo.deleted).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(repo.deleted).containsExactly(listOf(PageId(2)))
        assertThat(deletions.pending.value).isEmpty()
    }

    @Test
    fun undoKeepsThePage() = runTest {
        val deletions = PageDeletions(repo, backgroundScope)
        deletions.delete(PageId(1))
        advanceTimeBy(1_000)
        assertThat(deletions.undo(PageId(1))).isTrue()
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS * 2)
        assertThat(repo.deleted).isEmpty()
        assertThat(deletions.pending.value).isEmpty()
        assertThat(deletions.undo(PageId(1))).isFalse()
    }

    @Test
    fun aLongerWindowFromAccessibilitySettingsIsHonoured() = runTest {
        val deletions = PageDeletions(repo, backgroundScope)
        deletions.delete(PageId(1), windowMs = 20_000)
        deletions.delete(PageId(2), windowMs = 20_000)
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS + 1)
        runCurrent()
        // Past the usual six seconds both are still waiting, and Undo still works.
        assertThat(deletions.pending.value).containsExactly(PageId(1), PageId(2))
        assertThat(deletions.undo(PageId(1))).isTrue()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(repo.deleted).containsExactly(listOf(PageId(2)))
        assertThat(deletions.pending.value).isEmpty()
    }

    @Test
    fun failedDeleteBringsThePageBack() = runTest {
        repo.failWrites = true
        val deletions = PageDeletions(repo, backgroundScope)
        val failure = backgroundScope.launch { assertThat(deletions.failed.first()).isEqualTo(PageId(2)) }
        runCurrent()
        deletions.delete(PageId(2))
        advanceTimeBy(PageDeletions.UNDO_WINDOW_MS + 1)
        runCurrent()
        assertThat(failure.isCompleted).isTrue()
        assertThat(deletions.pending.value).isEmpty()
    }
}
