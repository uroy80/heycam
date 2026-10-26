package heycam.feature.scan.review

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.model.ImportTarget
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedPage
import heycam.feature.scan.AtFontScale
import heycam.feature.scan.stagedImage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ReviewScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val preview: PreviewLoader = { _, _, _ -> Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888) }

    private fun state(
        target: ImportTarget = ImportTarget.NewDocument(),
        pages: List<StagedPage> = (1..3).map { StagedPage(stagedImage("p$it")) },
        current: Int = 0,
    ) = ReviewUiState(loading = false, session = ScanSession("s1", target, pages, Instant.EPOCH), current = current)

    private fun show(state: ReviewUiState, callbacks: ReviewCallbacks = ReviewCallbacks(), fontScale: Float = 1f) {
        rule.setContent { MaterialTheme { AtFontScale(fontScale) { ReviewScreen(state, preview, SnackbarHostState(), callbacks) } } }
    }

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    /** The label's own text node: laid out whole (not cut off, not ellipsised) on at most [lines] line(s). */
    private fun assertLabelFits(text: String, lines: Int = 1) {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        assertThat(layout.didOverflowHeight).isFalse()
        assertThat((0 until layout.lineCount).none { layout.isLineEllipsized(it) }).isTrue()
        assertThat(layout.lineCount).isAtMost(lines)
    }

    @Test
    fun showsPageCountAndSaveLabel() {
        show(state())
        rule.onNodeWithContentDescription("Page 1 of 3").assertIsDisplayed()
        rule.onNodeWithText("Save 3 pages").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete page").assertIsDisplayed()
        rule.onNodeWithContentDescription("Tip").assertDoesNotExist()
    }

    @Test
    fun filterStripIsARadioGroup() {
        var picked: PageFilter? = null
        show(state(), ReviewCallbacks(onFilter = { picked = it }))
        rule.onNodeWithContentDescription("Original").assertIsSelected()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton).and(hasDescription("B&W")))
            .performClick()
        assertThat(picked).isEqualTo(PageFilter.BLACK_WHITE)
    }

    @Test
    fun applyToAllAppearsWhenPagesDiffer() {
        val pages = listOf(
            StagedPage(stagedImage("a")).let { it.copy(edits = it.edits.copy(filter = PageFilter.AUTO)) },
            StagedPage(stagedImage("b")),
        )
        var applied: PageFilter? = null
        show(state(pages = pages), ReviewCallbacks(onApplyToAll = { applied = it }))
        rule.onNodeWithText("Use Auto on all 2 pages").performClick()
        assertThat(applied).isEqualTo(PageFilter.AUTO)
    }

    @Test
    fun backAsksBeforeDiscarding() {
        var discarded = false
        show(state(), ReviewCallbacks(onDiscard = { discarded = true }))
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Discard 3 pages?").assertIsDisplayed()
        rule.onNodeWithText("Discard").performClick()
        assertThat(discarded).isTrue()
    }

    @Test
    fun systemBackAsksBeforeDiscarding() {
        show(state())
        pressBack()
        rule.onNodeWithText("Discard 3 pages?").assertIsDisplayed()
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun systemBackWhileSavingKeepsTheScreenAndAsksNothing() {
        var discarded = false
        show(state().copy(busy = ReviewBusy.SAVING), ReviewCallbacks(onDiscard = { discarded = true }))
        pressBack()
        rule.onNodeWithText("Save 3 pages").assertIsDisplayed()
        rule.onNodeWithText("Discard 3 pages?").assertDoesNotExist()
        assertThat(rule.activity.isFinishing).isFalse()
        assertThat(discarded).isFalse()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text metrics
    @Config(qualifiers = "w360dp-h740dp")
    fun doubleFontSizeKeepsTheActionsWhole() {
        show(state(), fontScale = 2f)
        rule.onNodeWithText("Save 3 pages").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Add page").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        assertLabelFits("Save 3 pages")
        assertLabelFits("Add page")
        rule.onNodeWithContentDescription("Original").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeKeepsThePageLarge() {
        show(state())
        rule.onNodeWithContentDescription("Page 1 of 3, Original").assertHeightIsAtLeast(200.dp)
        rule.onNodeWithText("Save 3 pages").assertIsDisplayed()
        rule.onNodeWithContentDescription("B&W").assertIsDisplayed()
    }

    @Test
    fun addPageOffersScanAndPhotos() {
        var photos = false
        show(state(), ReviewCallbacks(onAddPhotos = { photos = true }))
        rule.onNodeWithText("Add page").performClick()
        rule.onNodeWithText("Scan").assertIsDisplayed()
        rule.onNodeWithText("Photos").performClick()
        assertThat(photos).isTrue()
    }

    @Test
    fun retakeReplacesOnePage() {
        show(state(ImportTarget.Replace(PageId(4)), pages = listOf(StagedPage(stagedImage("r")))))
        rule.onNodeWithText("Replace page").assertIsDisplayed()
        rule.onNodeWithText("Add page").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete page").assertDoesNotExist()
    }

    @Test
    fun importedPhotosGetTheStraighteningTip() {
        show(state(pages = listOf(StagedPage(stagedImage("ph", PageOrigin.PHOTO_PICKER)))))
        rule.onNodeWithContentDescription("Tip").performClick()
        rule.onNodeWithText("To straighten a photo, use Scan → gallery.").assertIsDisplayed()
    }

    @Test
    fun savingDisablesActions() {
        var saved = false
        show(state().copy(busy = ReviewBusy.SAVING), ReviewCallbacks(onSave = { saved = true }))
        rule.onNodeWithText("Save 3 pages").performClick()
        assertThat(saved).isFalse()
    }

    private fun hasDescription(text: String) = SemanticsMatcher("contentDescription = $text") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(text)
    }
}
