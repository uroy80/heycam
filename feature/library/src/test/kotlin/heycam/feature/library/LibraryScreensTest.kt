package heycam.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.model.DocumentId
import heycam.core.model.PageId
import heycam.core.model.SearchHit
import heycam.core.model.Snippet
import heycam.feature.library.folder.FolderCallbacks
import heycam.feature.library.folder.FolderScreen
import heycam.feature.library.folder.FolderUiState
import heycam.feature.library.search.SearchResults
import heycam.feature.library.search.SearchScreen
import heycam.feature.library.trash.TrashScreen
import heycam.feature.library.trash.TrashUiState
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class LibraryScreensTest {
    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun searchShowsTitlesThenTextHits() {
        val lease = summary(1, "Lease agreement")
        val results = SearchResults.Done(
            query = "deposit",
            titles = listOf(SearchHit.TitleHit(summary(2, "Deposit receipt"), 0 until 7)),
            inText = listOf(SearchHit.TextHit(lease, PageId(10), 2, Snippet("…pay a deposit of four weeks…", listOf(7 until 14)))),
        )
        var opened: Triple<DocumentId, Int?, String?>? = null
        rule.setContent {
            MaterialTheme { SearchScreen("deposit", results, today, {}, {}, { id, page, hl -> opened = Triple(id, page, hl) }) }
        }
        rule.onNodeWithContentDescription("Titles").assertIsDisplayed()
        rule.onNodeWithContentDescription("In the text").assertIsDisplayed()
        rule.onNodeWithContentDescription("Lease agreement, p. 3: …pay a deposit of four weeks…").performClick()
        assertThat(opened).isEqualTo(Triple(DocumentId(1), 2, "deposit"))
        rule.onNodeWithContentDescription("Deposit receipt", substring = true).performClick()
        assertThat(opened).isEqualTo(Triple(DocumentId(2), null, null))
    }

    @Test
    fun searchWithNothingFound() {
        rule.setContent { MaterialTheme { SearchScreen("zzz", SearchResults.Done("zzz", emptyList(), emptyList()), today, {}, {}, { _, _, _ -> }) } }
        rule.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test
    fun searchClearButton() {
        var query = "lease"
        rule.setContent { MaterialTheme { SearchScreen(query, SearchResults.Idle, today, { query = it }, {}, { _, _, _ -> }) } }
        rule.onNodeWithContentDescription("Clear").performClick()
        assertThat(query).isEmpty()
    }

    @Test
    fun trashRestoreAndEmpty() {
        var restored: List<DocumentId>? = null
        var emptied = false
        val state = TrashUiState(loaded = true, documents = listOf(summary(5, "Old receipt", trashed = true)))
        rule.setContent {
            MaterialTheme {
                TrashScreen(state, SnackbarHostState(), {}, {}, {}, { restored = it }, {}, { emptied = true })
            }
        }
        rule.onNodeWithContentDescription("Restore").performClick()
        assertThat(restored).containsExactly(DocumentId(5))
        rule.onNodeWithContentDescription("Empty trash").assertIsDisplayed()
        rule.onNodeWithText("Empty").performClick()
        rule.onNodeWithText("Delete 1 document for good?").assertIsDisplayed()
        assertThat(emptied).isFalse()
        rule.onNodeWithText("Delete").performClick()
        assertThat(emptied).isTrue()
    }

    @Test
    fun trashRowOffersDeleteForGoodToTalkBack() {
        var deleted: List<DocumentId>? = null
        val state = TrashUiState(loaded = true, documents = listOf(summary(5, "Old receipt", trashed = true)))
        rule.setContent { MaterialTheme { TrashScreen(state, SnackbarHostState(), {}, {}, {}, {}, { deleted = it }, {}) } }
        val node = rule.onNodeWithContentDescription("Old receipt", substring = true).fetchSemanticsNode()
        rule.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Delete for good" }.action() }
        rule.onNodeWithText("Delete").performClick()
        assertThat(deleted).containsExactly(DocumentId(5))
    }

    @Test
    fun emptyTrash() {
        rule.setContent { MaterialTheme { TrashScreen(TrashUiState(loaded = true), SnackbarHostState(), {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Trash is empty").assertIsDisplayed()
        rule.onNodeWithText("Empty").assertDoesNotExist()
    }

    @Test
    fun folderShowsPathAndAsksBeforeDeleting() {
        var deleted = false
        var opened: Long? = null
        val state = FolderUiState(
            loaded = true,
            folder = folder(3, "Statements", parent = 2),
            path = listOf(folder(1, "Money"), folder(2, "Bank", parent = 1)),
            subfolders = listOf(folder(4, "2025", parent = 3, count = 2)),
            documents = listOf(summary(1, "May statement", folder = 3)),
        )
        rule.setContent {
            MaterialTheme {
                FolderScreen(state, today, SnackbarHostState(), FolderCallbacks(onDeleteFolder = { deleted = true }, onOpenFolder = { opened = it.value }))
            }
        }
        rule.onNodeWithText("Statements").assertIsDisplayed()
        rule.onNodeWithContentDescription("Money › Bank").assertIsDisplayed()
        rule.onNodeWithContentDescription("2025, 2 docs").performClick()
        assertThat(opened).isEqualTo(4L)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("New folder").assertDoesNotExist() // three levels deep already
        rule.onNodeWithText("Delete folder").performClick()
        rule.onNodeWithText("Its documents move to Bank.").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertThat(deleted).isTrue()
    }
}
