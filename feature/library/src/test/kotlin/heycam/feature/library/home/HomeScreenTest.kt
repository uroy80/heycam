package heycam.feature.library.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.model.DocumentId
import heycam.core.model.LibraryStats
import heycam.core.model.SortOrder
import heycam.feature.library.AtFontScale
import heycam.feature.library.folder
import heycam.feature.library.summary
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 20)
    private val lead = summary(4, "Receipt", updated = 4)
    private val full = HomeUiState(
        loaded = true,
        stats = LibraryStats(3, 7, 300_000),
        latest = lead,
        pinned = listOf(summary(2, "Passport copy", pinned = true)),
        sections = listOf(FolderSection(folder(10, "Bank", count = 1), listOf(summary(3, "Bank letter", folder = 10)))),
        documents = listOf(summary(1, "Old lease")),
    )

    private fun show(state: HomeUiState, callbacks: HomeCallbacks = HomeCallbacks(), fontScale: Float = 1f) {
        rule.setContent {
            MaterialTheme {
                AtFontScale(fontScale) {
                    HomeScreen(state, today, toolsTab = { Text("Tools go here") }, scanStatus = {}, snackbar = SnackbarHostState(), callbacks = callbacks)
                }
            }
        }
    }

    private fun customAction(label: String) = SemanticsMatcher("has custom action $label") { node ->
        node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.any { it.label == label }
    }

    @Test
    fun firstRunOffersScanAndImport() {
        var scanned = false
        var imported = false
        show(HomeUiState(loaded = true), HomeCallbacks(onScan = { scanned = true }, onImport = { imported = true }))
        rule.onNodeWithText("Scan your first page.").assertIsDisplayed()
        rule.onNodeWithText("Scans stay on this phone").assertIsDisplayed()
        rule.onNodeWithText("Scan a document").performClick()
        rule.onNodeWithText("Import photos").performClick()
        assertThat(scanned).isTrue()
        assertThat(imported).isTrue()
        rule.onNodeWithText("Scan").assertDoesNotExist() // the scan bar waits until there is something to show
    }

    @Test
    fun mastheadAndDateline() {
        var searched = false
        var settings = false
        show(full, HomeCallbacks(onSearch = { searched = true }, onSettings = { settings = true }))
        rule.onNodeWithContentDescription("heycam").assertIsDisplayed()
        rule.onNodeWithContentDescription("On this phone, 3 documents, 7 pages, Tuesday", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertThat(searched).isTrue()
        assertThat(settings).isTrue()
    }

    @Test
    fun datelineIsSpokenInWords() {
        show(full.copy(stats = LibraryStats(1, 1, 1_000)))
        val spoken = rule.onNodeWithContentDescription("On this phone", substring = true)
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertThat(spoken).startsWith("On this phone, 1 document, 1 page, ")
        assertThat(spoken).doesNotContain("pp")
        assertThat(spoken).doesNotContain("docs")
    }

    @Test
    fun contentsListLeadPinnedFoldersAndRows() {
        var opened: DocumentId? = null
        var folderOpened = false
        show(full, HomeCallbacks(onOpenDocument = { opened = it }, onOpenFolder = { folderOpened = true }))
        rule.onNodeWithContentDescription("Latest: Receipt", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Pinned").assertIsDisplayed()
        rule.onNodeWithContentDescription("Bank")
            .assert(SemanticsMatcher("open folder label") { it.config[SemanticsActions.OnClick].label == "Open folder" })
            .performClick()
        assertThat(folderOpened).isTrue()
        rule.onNodeWithContentDescription("Old lease", substring = true).performClick()
        assertThat(opened).isEqualTo(DocumentId(1))
    }

    @Test
    fun leadStoryIsOneTalkBackStopWithItsOwnButtons() {
        var opened: DocumentId? = null
        var shared: List<DocumentId>? = null
        show(full, HomeCallbacks(onOpenDocument = { opened = it }, onExport = { shared = it }))
        // The title, meta line, flag and kicker are read once, by the story node.
        rule.onAllNodes(hasText("Receipt") or hasContentDescription("Receipt", substring = true)).assertCountEquals(1)
        rule.onAllNodes(hasText("Latest") or hasContentDescription("Latest")).assertCountEquals(0)
        rule.onNodeWithContentDescription("Latest: Receipt", substring = true)
            .assert(customAction("Select")).assert(customAction("Move")).assert(customAction("Rename")).assert(customAction("Delete"))
            .performClick()
        assertThat(opened).isEqualTo(DocumentId(4))
        rule.onNodeWithText("Share").performClick()
        assertThat(shared).containsExactly(DocumentId(4))
    }

    @Test
    fun rowsCarryTalkBackActions() {
        var trashed: List<DocumentId>? = null
        show(full, HomeCallbacks(onTrash = { trashed = it }))
        val row = rule.onNodeWithContentDescription("Old lease", substring = true)
        row.assert(customAction("Select")).assert(customAction("Share")).assert(customAction("Move"))
            .assert(customAction("Rename")).assert(customAction("Delete"))
        val node = row.fetchSemanticsNode()
        rule.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Delete" }.action() }
        assertThat(trashed).containsExactly(DocumentId(1))
    }

    @Test
    fun longPressSelects() {
        var toggled: DocumentId? = null
        show(full, HomeCallbacks(onToggleSelect = { toggled = it }))
        rule.onNodeWithContentDescription("Old lease", substring = true).performTouchInput { longClick() }
        assertThat(toggled).isEqualTo(DocumentId(1))
    }

    @Test
    fun selectionBarActsOnTheSelection() {
        var exported: List<DocumentId>? = null
        var cleared = false
        val selected = full.copy(selection = setOf(DocumentId(1), DocumentId(4)))
        show(selected, HomeCallbacks(onExport = { exported = it }, onClearSelection = { cleared = true }))
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Old lease", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithContentDescription("Share").performClick()
        assertThat(exported).containsExactly(DocumentId(1), DocumentId(4))
        rule.onNodeWithContentDescription("Clear selection").performClick()
        assertThat(cleared).isTrue()
    }

    @Test
    fun sortMenuAndTools() {
        var sort: SortOrder? = null
        show(full, HomeCallbacks(onSort = { sort = it }))
        rule.onNodeWithContentDescription("Sort: Recent").performClick()
        rule.onNodeWithText("Name").performClick()
        assertThat(sort).isEqualTo(SortOrder.TITLE_ASC)
        rule.onNodeWithText("Tools").performClick()
        rule.onNodeWithText("Tools").assertIsSelected()
        rule.onNodeWithText("Tools go here").assertIsDisplayed()
    }

    @Test
    fun overflowOpensTrash() {
        var trash = false
        show(full, HomeCallbacks(onOpenTrash = { trash = true }))
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Trash").performClick()
        assertThat(trash).isTrue()
    }

    @Test
    // Robolectric never idles after typing into a dialog's field on the tall test phone, so this one runs smaller.
    @Config(qualifiers = "w320dp-h470dp")
    fun newFolderRefusesANameInUse() {
        var created: String? = null
        show(full, HomeCallbacks(onCreateFolder = { created = it }, loadFolderNames = { setOf("bank") }))
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("New folder").performClick()
        rule.onNodeWithText("Name").performTextReplacement("BANK")
        rule.onNodeWithText("Name in use").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsNotEnabled().performClick()
        assertThat(created).isNull()
        rule.onNodeWithText("BANK").performTextReplacement("Taxes")
        rule.onNodeWithText("Name in use").assertDoesNotExist()
        rule.onNodeWithText("Create").performClick()
        assertThat(created).isEqualTo("Taxes")
    }

    @Test
    fun overflowMenuWorksInNightInk() {
        var trash = false
        var folder = false
        rule.setContent {
            HeycamTheme(ThemeMode.NightInk) {
                HomeScreen(
                    full, today, toolsTab = {}, scanStatus = {}, snackbar = SnackbarHostState(),
                    callbacks = HomeCallbacks(onOpenTrash = { trash = true }, onCreateFolder = { folder = true }),
                )
            }
        }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("New folder").assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithText("Trash").assertIsDisplayed().performClick()
        assertThat(trash).isTrue()
        assertThat(folder).isFalse()
        rule.onNodeWithText("New folder").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w308dp-h640dp")
    fun theLargestDisplaySizeKeepsBothTabsWhole() {
        show(full)
        val tools = rule.onNodeWithText("Tools").getUnclippedBoundsInRoot()
        val sort = rule.onNodeWithContentDescription("Sort: Recent").assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertThat(tools.right).isAtMost(sort.left)
        rule.onNodeWithText("Recent").assertDoesNotExist() // the icon alone
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun largeFontKeepsTheOverflowMenuAndSort() {
        show(full, fontScale = 2f)
        rule.onNodeWithContentDescription("More options").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Sort: Recent").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Contents").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeScrollsTheMastheadAwayWithTheContents() {
        show(full)
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Old lease", substring = true))
        rule.onNodeWithContentDescription("Old lease", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Search").assertIsNotDisplayed()
    }
}
