package heycam.feature.document

import android.content.ClipDescription
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.model.OcrScript
import heycam.core.model.PageId
import heycam.core.model.TextStatus
import heycam.feature.document.pages.PageGrid
import heycam.feature.document.pages.PageGridActions
import heycam.feature.document.pages.ReorderState
import heycam.feature.document.text.findMatches
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class DocumentScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val pages = listOf(page(1, 0), page(2, 1, status = TextStatus.PENDING), page(3, 2))
    private val texts = mapOf(
        PageId(1) to pageText(1, "The tenant will pay a deposit."),
        PageId(3) to pageText(3, "The deposit is held."),
    )
    private val state = DocumentUiState(loaded = true, document = document(pages = pages), pages = pages, texts = texts)

    private fun find(query: String) =
        FindState(query, findMatches(pages.map { it.id to texts[it.id]?.text }, query), current = 0)

    private fun show(
        tab: DocumentTab,
        find: FindState = FindState(),
        callbacks: DocumentCallbacks = DocumentCallbacks(),
        state: DocumentUiState = this.state,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            MaterialTheme {
                AtFontScale(fontScale) {
                    DocumentScreen(state, tab, find, initialPage = null, snackbar = SnackbarHostState(), callbacks = callbacks)
                }
            }
        }
    }

    private fun pressBack() {
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun customAction(label: String) = SemanticsMatcher("has custom action $label") { node ->
        node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.any { it.label == label }
    }

    private fun runCustomAction(nodeDescription: String, label: String) {
        val node = rule.onNodeWithContentDescription(nodeDescription).fetchSemanticsNode()
        rule.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
    }

    @Test
    fun pagesTabShowsTheGridWithTalkBackMoves() {
        var moved: Pair<Int, Int>? = null
        var opened: Int? = null
        show(DocumentTab.PAGES, callbacks = DocumentCallbacks(onMovePage = { f, t -> moved = f to t }, onOpenPage = { opened = it }))
        rule.onNodeWithText("Lease agreement").assertIsDisplayed()
        rule.onNodeWithText("Pages").assertIsSelected()
        rule.onNodeWithContentDescription("Reading 1 page").assertIsDisplayed()

        rule.onNodeWithContentDescription("Page 1 of 3")
            .assert(customAction("Move later"))
            .assert(customAction("Move to end"))
            .assert(customAction("Move to position…"))
            .assert(customAction("Delete page"))
            .assert(customAction("Move earlier").not())
        rule.onNodeWithContentDescription("Page 2 of 3")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Reading text"))

        runCustomAction("Page 3 of 3", "Move to start")
        assertThat(moved).isEqualTo(2 to 0)
        rule.onNodeWithContentDescription("Page 2 of 3").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun talkBackMovesAndTurnsAreAnnounced() {
        show(DocumentTab.PAGES)
        runCustomAction("Page 3 of 3", "Move to start")
        rule.onNodeWithContentDescription("Page moved to 1").assertExists()
        runCustomAction("Page 2 of 3", "Rotate")
        rule.onNodeWithContentDescription("Rotated").assertExists()
        rule.onNodeWithContentDescription("Page moved to 1").assertDoesNotExist()
    }

    @Test
    fun moveToPositionIsOfferedToTalkBack() {
        var asked: Int? = null
        rule.setContent {
            MaterialTheme {
                val reorder = remember { ReorderState(androidx.compose.foundation.lazy.grid.LazyGridState()) }
                PageGrid(pages, highlightIndex = null, reorder = reorder, actions = PageGridActions(onMoveToPosition = { asked = it }))
            }
        }
        runCustomAction("Page 2 of 3", "Move to position…")
        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun positionIsOneBasedAndInRange() {
        assertThat(parsePosition("3", page = 0, count = 3)).isEqualTo(2)
        assertThat(parsePosition(" 1 ", page = 2, count = 3)).isEqualTo(0)
        assertThat(parsePosition("1", page = 0, count = 3)).isNull()
        assertThat(parsePosition("4", page = 0, count = 3)).isNull()
        assertThat(parsePosition("0", page = 0, count = 3)).isNull()
        assertThat(parsePosition("", page = 0, count = 3)).isNull()
    }

    @Test
    fun addPageAndExport() {
        var scanned = false
        var exported = false
        show(DocumentTab.PAGES, callbacks = DocumentCallbacks(onScanMore = { scanned = true }, onExport = { exported = true }))
        rule.onNodeWithText("Add page").performClick()
        rule.onNodeWithText("Scan").performClick()
        rule.onNodeWithText("Export").performClick()
        assertThat(scanned).isTrue()
        assertThat(exported).isTrue()
    }

    @Test
    fun trashAsksFirst() {
        var trashed = false
        show(DocumentTab.PAGES, callbacks = DocumentCallbacks(onTrash = { trashed = true }))
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Move to trash").performClick()
        rule.onNodeWithText("Move this document to trash?").assertIsDisplayed()
        assertThat(trashed).isFalse()
        rule.onNodeWithText("Move to trash").performClick()
        assertThat(trashed).isTrue()
    }

    @Test
    fun textTabShowsTextFindAndTheNote() {
        var next = 0
        show(DocumentTab.TEXT, find("deposit"), DocumentCallbacks(onNextMatch = { next++ }))
        rule.onNodeWithText("1 / 2").assertIsDisplayed().assertContentDescriptionEquals("Match 1 of 2")
        rule.onNodeWithContentDescription("Next match").performClick()
        assertThat(next).isEqualTo(1)
        rule.onNodeWithText("Recognised on this phone. Check names and numbers.").assertIsDisplayed()
        rule.onNodeWithText("Copy all").assertIsEnabled()
        rule.onNodeWithContentDescription("Recognised text · page 1 of 3").assertIsDisplayed()
    }

    @Test
    fun findCountAndFramesAreSpokenInWords() {
        show(DocumentTab.TEXT, find("zzz"))
        rule.onNodeWithText("0 / 0").assertContentDescriptionEquals("No matches")
    }

    @Test
    fun frameStripSaysWhichPageIsCurrentAndWhichHaveMatches() {
        show(DocumentTab.TEXT, find("deposit"))
        val frame = { n: Int -> rule.onNode(hasContentDescription("Page $n of 3") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)) }
        frame(1).assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Has matches"))
        frame(2).assertIsNotSelected().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        frame(3).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Has matches"))
    }

    @Test
    fun readAgainOnlyStartsFromItsButton() {
        val picked = mutableListOf<OcrScript?>()
        show(DocumentTab.TEXT, callbacks = DocumentCallbacks(onReadAgain = { picked += it }))
        rule.onNodeWithText("Language").performClick()
        rule.onNodeWithText("Read again as").assertIsDisplayed()
        // Checking (or tapping) the current choice reads nothing again.
        rule.onNodeWithText("Auto").assertIsSelected().performClick()
        rule.onNodeWithText("Devanagari").performClick()
        rule.onNodeWithText("Devanagari").assertIsSelected()
        assertThat(picked).isEmpty()
        rule.onNodeWithText("Read again").performClick()
        assertThat(picked).containsExactly(OcrScript.DEVANAGARI)
        rule.onNodeWithText("Read again as").assertDoesNotExist()
    }

    @Test
    fun eachPageHasItsOwnEdit() {
        var saved: Pair<PageId, String>? = null
        show(DocumentTab.TEXT, callbacks = DocumentCallbacks(onSaveText = { id, text -> saved = id to text }))
        // Page 2 is still being read, so it has nothing to edit.
        rule.onNodeWithContentDescription("Edit page 2").assertDoesNotExist()
        rule.onNodeWithContentDescription("Edit page 3").performClick()
        rule.onNodeWithText("Editing page 3").assertIsDisplayed()
        rule.onNodeWithText("The deposit is held.").performTextReplacement("The deposit is returned.")
        rule.onNodeWithText("Save").performClick()
        assertThat(saved).isEqualTo(PageId(3) to "The deposit is returned.")
        rule.onNodeWithText("Editing page 3").assertDoesNotExist()
    }

    @Test
    fun editingKeepsSaveInTheTopBarAndClearsTheRest() {
        show(DocumentTab.TEXT)
        rule.onNodeWithContentDescription("Edit page 1").performClick()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Recognised on this phone. Check names and numbers.").assertDoesNotExist()
        rule.onNodeWithText("Copy all").assertDoesNotExist()
        rule.onNodeWithText("Pages").assertDoesNotExist()
        rule.onNodeWithContentDescription("Edit page 3").assertDoesNotExist()
    }

    @Test
    fun backDuringAnEditAsksBeforeDroppingIt() {
        var left = false
        show(DocumentTab.TEXT, callbacks = DocumentCallbacks(onBack = { left = true }))
        rule.onNodeWithContentDescription("Edit page 1").performClick()
        rule.onNodeWithText("The tenant will pay a deposit.").performTextReplacement("The tenant pays.")
        pressBack()
        rule.onNodeWithText("Discard your edit?").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithText("The tenant pays.").assertIsDisplayed()
        pressBack()
        rule.onNodeWithText("Discard").performClick()
        rule.onNodeWithText("Editing page 1").assertDoesNotExist()
        rule.onNodeWithText("The tenant will pay a deposit.").assertIsDisplayed()
        assertThat(left).isFalse()
    }

    @Test
    fun backWithoutChangesJustEndsTheEdit() {
        var left = false
        show(DocumentTab.TEXT, callbacks = DocumentCallbacks(onBack = { left = true }))
        rule.onNodeWithContentDescription("Edit page 1").performClick()
        pressBack()
        rule.onNodeWithText("Discard your edit?").assertDoesNotExist()
        rule.onNodeWithText("Editing page 1").assertDoesNotExist()
        assertThat(left).isFalse()
    }

    @Test
    fun backClosesFindBeforeLeaving() {
        var left = false
        var query: String? = null
        show(DocumentTab.TEXT, find("deposit"), DocumentCallbacks(onBack = { left = true }, onQuery = { query = it }))
        rule.onNodeWithContentDescription("Close find").assertIsDisplayed()
        pressBack()
        rule.onNodeWithContentDescription("Close find").assertDoesNotExist()
        assertThat(query).isEmpty()
        assertThat(left).isFalse()
    }

    @Test
    fun copyAndShareText() {
        var copied = false
        var shared = false
        show(DocumentTab.TEXT, callbacks = DocumentCallbacks(onCopyAll = { copied = true }, onShareText = { shared = true }))
        rule.onNodeWithText("Copy all").performClick()
        rule.onNodeWithText("Share text").performClick()
        assertThat(copied).isTrue()
        assertThat(shared).isTrue()
    }

    @Test
    fun copiedTextIsMarkedSensitive() {
        val clip = sensitiveClip("Lease agreement", "The tenant will pay a deposit.")
        assertThat(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo("The tenant will pay a deposit.")
        assertThat(clip.description.label.toString()).isEqualTo("Lease agreement")
    }

    @Test
    fun textFlagOnlyWithText() {
        val photo = listOf(page(1, 0, status = TextStatus.EMPTY))
        show(DocumentTab.PAGES, state = DocumentUiState(loaded = true, document = document(pages = photo), pages = photo))
        rule.onNodeWithContentDescription("Text").assertDoesNotExist()
    }

    @Test
    fun gridWithOnePageOffersNoMovesOrDelete() {
        val single = listOf(page(1, 0))
        rule.setContent {
            MaterialTheme {
                val reorder = remember { ReorderState(androidx.compose.foundation.lazy.grid.LazyGridState()) }
                PageGrid(single, highlightIndex = 0, reorder = reorder, actions = PageGridActions())
            }
        }
        rule.onNodeWithContentDescription("Page 1 of 1")
            .assert(customAction("Rotate"))
            .assert(customAction("Delete page").not())
            .assert(customAction("Move to position…").not())
    }

    @Test
    fun theOnlyPageCannotBeDraggedOntoDelete() {
        val single = listOf(page(1, 0))
        show(DocumentTab.PAGES, state = DocumentUiState(loaded = true, document = document(pages = single), pages = single))
        dragFirstPage()
        rule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun aPageCanBeDraggedOntoDeleteWhenThereAreOthers() {
        show(DocumentTab.PAGES)
        dragFirstPage()
        rule.onNodeWithText("Delete").assertIsDisplayed()
    }

    /** Presses page 1 and holds it: a long press lifts it for dragging. */
    private fun dragFirstPage() {
        rule.onNode(hasContentDescription("Page 1 of", substring = true)).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text metrics
    @Config(qualifiers = "w360dp-h740dp")
    fun doubleFontSizeKeepsThePagesActionsWhole() {
        show(DocumentTab.PAGES, fontScale = 2f)
        rule.onNodeWithText("Export").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Add page").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Export", useUnmergedTree = true).assertTextFits()
        rule.onNodeWithText("Add page", useUnmergedTree = true).assertTextFits()
        rule.onNodeWithContentDescription("More options").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h740dp")
    fun doubleFontSizeKeepsTheTextActionsWhole() {
        show(DocumentTab.TEXT, fontScale = 2f)
        rule.onNodeWithText("Copy all").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Copy all", useUnmergedTree = true).assertTextFits()
        rule.onNodeWithText("Share text").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Language").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeShowsTheRecognisedText() {
        show(DocumentTab.TEXT)
        rule.onNodeWithText("The tenant will pay a deposit.").assertIsDisplayed()
        rule.onNodeWithText("Copy all").assertIsDisplayed()
        rule.onNode(hasText("Pages")).assertIsDisplayed()
    }
}
