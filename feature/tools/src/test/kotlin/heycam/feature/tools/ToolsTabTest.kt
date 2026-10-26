package heycam.feature.tools

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ToolsTabTest {
    @get:Rule val rule = createComposeRule()

    private val picked = mutableListOf<Tool>()

    private fun show() = rule.setContent { HeycamTheme(ThemeMode.DayPaper) { ToolsTab(onTool = { picked += it }) } }

    @Test
    fun everyWorkingToolIsATargetInOrder() {
        show()
        val labels = listOf("Scan to PDF", "Import photos", "Compress a document", "Extract text", "Print")
        labels.forEach { label ->
            rule.onNode(hasText(label) and hasClickAction()).performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        assertThat(picked).containsExactly(Tool.SCAN_TO_PDF, Tool.IMPORT_PHOTOS, Tool.COMPRESS, Tool.EXTRACT_TEXT, Tool.PRINT).inOrder()
    }

    @Test
    fun rowNumbersAreNotReadAloud() {
        show()
        rule.onNode(hasText("01")).assertDoesNotExist()
    }

    @Test
    fun heypdfShowsTheDisclosureBeforeOpeningTheBrowser() {
        show()
        rule.onNodeWithText("More PDF tools on heypdf.edgr.in").performScrollTo().performClick()
        rule.onNodeWithText(
            "Opens heypdf.edgr.in in your browser. Files you upload there leave your phone and are processed on heypdf's server.",
        ).assertExists()
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertThat(shadowOf(app).nextStartedActivity).isNull()

        rule.onNodeWithText("Open").performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data).isEqualTo(Uri.parse("https://heypdf.edgr.in"))
        assertThat(intent.categories).contains(Intent.CATEGORY_BROWSABLE)
        assertThat(intent.extras).isNull() // nothing of the user's travels with the link
        assertThat(picked).isEmpty()
        rule.onNodeWithText("Open").assertDoesNotExist()
    }

    @Test
    fun cancellingTheDisclosureOpensNothing() {
        show()
        rule.onNodeWithText("More PDF tools on heypdf.edgr.in").performScrollTo().performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Open").assertDoesNotExist()
        assertThat(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity).isNull()
    }
}
