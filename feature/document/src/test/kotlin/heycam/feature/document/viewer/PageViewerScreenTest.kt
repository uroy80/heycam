package heycam.feature.document.viewer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.model.Page
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.feature.document.document
import heycam.feature.document.page
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class PageViewerScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val preview: PagePreviewLoader = { _, _, _ -> Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888) }

    private fun show(pages: List<Page>, current: Int, callbacks: ViewerCallbacks) {
        val state = ViewerUiState(loaded = true, document = document(pages = pages), pages = pages, current = current)
        rule.setContent { MaterialTheme { PageViewerScreen(state, preview, SnackbarHostState(), callbacks) } }
    }

    @Test
    fun actionsWorkOnTheCurrentPage() {
        var rotated = false
        var retake: Page? = null
        var deleted = false
        val pages = listOf(page(1, 0), page(2, 1), page(3, 2))
        show(pages, 1, ViewerCallbacks(onRotate = { rotated = true }, onRetake = { retake = it }, onDelete = { deleted = true }))
        rule.onNodeWithText("Page 2 of 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Lease agreement").assertIsDisplayed()
        rule.onNodeWithText("Rotate").performClick()
        rule.onNodeWithText("Retake").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertThat(rotated).isTrue()
        assertThat(retake!!.id).isEqualTo(PageId(2))
        assertThat(deleted).isTrue()
    }

    @Test
    fun filterStripAndApplyToAll() {
        var filter: PageFilter? = null
        var all: PageFilter? = null
        show(listOf(page(1, 0), page(2, 1)), 0, ViewerCallbacks(onFilter = { filter = it }, onFilterAll = { all = it }))
        rule.onNodeWithText("Filter").performClick()
        rule.onNodeWithContentDescription("Original").assertIsSelected()
        rule.onNodeWithContentDescription("Grey").performClick()
        rule.onNodeWithText("Use Original on all 2 pages").performClick()
        assertThat(filter).isEqualTo(PageFilter.GRAYSCALE)
        assertThat(all).isEqualTo(PageFilter.ORIGINAL)
    }

    @Test
    fun filterButtonSaysWhetherTheStripIsShownAndBackClosesIt() {
        var left = false
        show(listOf(page(1, 0), page(2, 1)), 0, ViewerCallbacks(onBack = { left = true }))
        val filter = rule.onNodeWithText("Filter")
        filter.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hidden"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .performClick()
        filter.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Shown"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        rule.onNodeWithContentDescription("Grey").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("Grey").assertDoesNotExist()
        filter.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hidden"))
        assertThat(left).isFalse()
    }

    @Test
    fun singlePageHasNoDelete() {
        show(listOf(page(1, 0)), 0, ViewerCallbacks())
        rule.onNodeWithText("Delete").assertDoesNotExist()
        rule.onNodeWithText("Retake").assertIsDisplayed()
    }
}
