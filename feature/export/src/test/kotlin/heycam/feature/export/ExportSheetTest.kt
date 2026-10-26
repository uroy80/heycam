package heycam.feature.export

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.export.ExportProgress
import heycam.core.model.DocumentId
import heycam.core.model.ExportFormat
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.SizePreset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ExportSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val oneDoc = ExportUiState(
        loading = false,
        documents = listOf(ExportDocument(DocumentId(1), "Lease agreement", pageCount = 8, pendingTextPages = 0)),
        fileName = "Lease agreement",
        estimates = mapOf(SizePreset.SMALL to 600_000L, SizePreset.STANDARD to 1_800_000L),
    )

    private val calls = mutableListOf<String>()

    private fun show(initial: ExportUiState) {
        var state by mutableStateOf(initial)
        rule.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                ExportSheet(
                    state = state,
                    onFormat = { calls += "format $it"; state = state.copy(format = it) },
                    onSize = { calls += "size $it"; state = state.copy(size = it) },
                    onPageSize = { calls += "page $it"; state = state.copy(pageSize = it) },
                    onMargin = { calls += "margin $it"; state = state.copy(margin = it) },
                    onSearchable = { calls += "searchable $it"; state = state.copy(searchable = it) },
                    onFileName = { calls += "name $it" },
                    onSave = { calls += "save" },
                    onShare = { calls += "share" },
                    onPrint = { calls += "print" },
                    onTextPendingAnswer = { calls += "wait $it" },
                    onTextPendingDismiss = { calls += "dismiss pending" },
                    onDismiss = { calls += "close" },
                )
            }
        }
    }

    @Test
    fun pdfShowsEveryControl() {
        show(oneDoc)
        rule.onNode(isHeading() and hasText("8 pages")).assertIsDisplayed()
        rule.onNodeWithContentDescription("Export").assertIsDisplayed()
        rule.onNodeWithText("PDF").assert(hasRole(Role.RadioButton)).assertIsSelected()
        rule.onNodeWithText("Standard").assertIsSelected()
        rule.onNodeWithText("Page size").assertExists()
        rule.onNodeWithText("Margin").assertExists()
        rule.onNodeWithText("Searchable PDF").performScrollTo().assert(hasRole(Role.Switch)).assertIsOn()
        rule.onNodeWithText("File name").performScrollTo().assertExists()
        rule.onNode(hasText("File name") and hasText(".pdf") and hasText("Lease agreement")).assertExists()
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Share").assertIsEnabled()
        rule.onNodeWithText("Print").assertIsEnabled()
    }

    @Test
    fun sizeEstimatesAreReadWithTheirChoice() {
        show(oneDoc)
        rule.onNode(hasText("Small") and hasText("600 kB")).assert(hasRole(Role.RadioButton))
        // TalkBack hears the estimate as a size, and never the placeholder while Original is still being measured.
        rule.onNode(hasText("Small")).assert(hasContentDescription("Small, about 600 kB"))
        rule.onNode(hasText("Original") and hasText("…")).assert(hasRole(Role.RadioButton)).assert(hasContentDescription("Original"))
    }

    @Test
    fun anUnmeasuredSizeIsMarked() {
        show(oneDoc.copy(estimates = oneDoc.estimates + (SizePreset.ORIGINAL to null)))
        rule.onNode(hasText("Original") and hasText("–")).assertExists()
        rule.onNode(hasText("…")).assertDoesNotExist()
    }

    @Test
    fun choosingAFormatReshapesTheSheet() {
        show(oneDoc)
        rule.onNodeWithText("JPG").performClick()
        assertThat(calls).contains("format JPEG")
        rule.onNodeWithText("Searchable PDF").assertDoesNotExist()
        rule.onNodeWithText("Page size").assertDoesNotExist()
        rule.onNode(hasText("File name") and hasText(".jpg")).assertExists()

        rule.onNodeWithText("Text").performClick()
        rule.onNodeWithText("Size").assertDoesNotExist()
    }

    @Test
    fun switchesAndSegmentsReportChoices() {
        show(oneDoc)
        rule.onNodeWithText("Small").performClick()
        rule.onNodeWithText("Letter").performClick()
        rule.onNodeWithText("Normal").performClick()
        rule.onNodeWithText("Searchable PDF").performScrollTo().performClick()
        rule.onNodeWithText("Searchable PDF").assertIsOff()
        assertThat(calls).containsAtLeast("size SMALL", "page LETTER", "margin NORMAL", "searchable false").inOrder()
    }

    @Test
    fun actionsAreWired() {
        show(oneDoc)
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performClick()
        rule.onNodeWithText("Print").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Close").performScrollTo().performClick()
        assertThat(calls).containsExactly("save", "share", "print", "close").inOrder()
    }

    @Test
    fun severalDocumentsHaveNoFileName() {
        show(
            oneDoc.copy(
                documents = listOf(
                    ExportDocument(DocumentId(1), "A", 2, 0),
                    ExportDocument(DocumentId(2), "B", 3, 0),
                ),
            ),
        )
        rule.onNode(isHeading() and hasText("2 documents")).assertExists()
        rule.onNodeWithText("File name").assertDoesNotExist()
    }

    @Test
    fun runningShowsProgressAndLocksActions() {
        show(oneDoc.copy(running = true, progress = ExportProgress(3, 8)))
        rule.onNodeWithText("Page 3 of 8").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Share").assertIsNotEnabled()
    }

    @Test
    fun errorsAreShort() {
        show(oneDoc.copy(error = ExportError.NO_SPACE))
        rule.onNodeWithText("Not enough space").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pendingTextAsks() {
        show(oneDoc.copy(textPendingPages = 2))
        rule.onNode(isHeading() and hasText("Text is still being read on 2 pages")).assertIsDisplayed()
        rule.onNodeWithText("Export without text").performClick()
        rule.onNodeWithText("Wait").performClick()
        assertThat(calls).containsExactly("wait false", "wait true").inOrder()
    }

    @Test
    fun nothingToExport() {
        show(ExportUiState(loading = false))
        rule.onNodeWithText("Nothing to export").assertIsDisplayed()
        rule.onAllNodesWithText("Close")[0].performClick()
        assertThat(calls).containsExactly("close")
    }

    @Test
    fun defaultsMatchTheModel() {
        // The sheet's segment orders cover every MVP value of the model enums.
        show(oneDoc.copy(pageSize = PageSize.FIT, margin = Margin.NONE, format = ExportFormat.PDF))
        rule.onNodeWithText("Fit").assertIsSelected()
        rule.onNodeWithText("None").assertIsSelected()
    }

    @Test
    fun everyTargetIsLabelledAndTalkBackStartsAtClose() {
        show(oneDoc)
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertWithMessage("clickable node ${node.id} at ${node.boundsInRoot}").that(label.joinToString("")).isNotEmpty()
        }
        val first = rule.onAllNodes(isFocusable()).fetchSemanticsNodes().first()
        assertThat(first.config.getOrNull(SemanticsProperties.ContentDescription)).containsExactly("Close")
    }

    @Test
    fun tappingOutsideCloses() {
        show(oneDoc)
        rule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        assertThat(calls).containsExactly("close")
    }

    @Test
    fun tappingOutsideOrBackNeverCancelsAnExport() {
        show(oneDoc.copy(running = true, progress = ExportProgress(1, 8)))
        rule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        assertThat(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isTrue()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertThat(rule.activity.isFinishing).isFalse()
        assertThat(calls).isEmpty()
    }

    @Test
    fun backIsLeftToTheDialogWhenIdle() {
        show(oneDoc)
        assertThat(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
}
