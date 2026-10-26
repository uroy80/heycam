package heycam.feature.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import heycam.core.data.StorageUsage
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
class StorageAndAboutScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun storage(state: StorageUiState) = rule.setContent {
        HeycamTheme(ThemeMode.DayPaper) {
            StorageScreen(
                state = state,
                onBack = { calls += "back" },
                onEmptyTrash = { calls += "empty" },
                onClearTemporaryFiles = { calls += "clear" },
                onOpenTrash = { calls += "trash" },
                snackbar = SnackbarHostState(),
            )
        }
    }

    @Test
    fun storageShowsEachFigureWithItsAction() {
        storage(StorageUiState(usage = StorageUsage(240_000_000, 12_000_000, 3_000_000), trashCount = 2))
        rule.onNode(hasText("Documents") and hasText("240 MB")).assertExists()
        rule.onNode(hasText("Trash") and hasText("12 MB")).assertExists()
        rule.onNode(hasText("Temporary files") and hasText("3.0 MB")).assertExists()

        rule.onNodeWithContentDescription("Clear, Temporary files").performClick()
        rule.onNodeWithContentDescription("Empty, Trash").performClick()
        // The same question, in the same words, as emptying the Trash screen.
        rule.onNode(isHeading() and hasText("Delete 2 documents for good?")).assertExists()
        rule.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.onNodeWithText("Open trash").performScrollTo().performClick()
        assertThat(calls).containsExactly("clear", "empty", "trash").inOrder()
    }

    @Test
    fun oneTrashedDocumentIsSingular() {
        storage(StorageUiState(usage = StorageUsage(1, 1, 1), trashCount = 1))
        rule.onNodeWithContentDescription("Empty, Trash").performClick()
        rule.onNodeWithText("Delete 1 document for good?").assertExists()
    }

    @Test
    fun cancellingTheEmptyQuestionKeepsTheTrash() {
        storage(StorageUiState(usage = StorageUsage(1, 1, 1), trashCount = 1))
        rule.onNodeWithContentDescription("Empty, Trash").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertThat(calls).isEmpty()
    }

    @Test
    fun nothingToFreeMeansNoAction() {
        storage(StorageUiState(usage = StorageUsage(5_000, 0, 0), trashCount = 0))
        rule.onNodeWithContentDescription("Empty, Trash").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Clear, Temporary files").assertIsNotEnabled()
    }

    @Test
    fun busyLocksTheActions() {
        storage(StorageUiState(usage = StorageUsage(5_000, 10, 10), trashCount = 1, busy = true))
        rule.onNodeWithContentDescription("Empty, Trash").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Clear, Temporary files").assertIsNotEnabled()
    }

    @Test
    fun unmeasuredFiguresStillOfferClearing() {
        storage(StorageUiState(usage = null, trashCount = 1))
        rule.onNodeWithContentDescription("Clear, Temporary files").assertIsEnabled()
        rule.onNode(hasText("Documents") and hasText("–")).assertExists()
    }

    @Test
    fun aboutLinksLeaveTheAppOnlyByIntent() {
        about()
        rule.onNodeWithText("Apache-2.0 · Uses Google ML Kit").performScrollTo().assertExists()

        rule.onNodeWithText("Privacy policy").performScrollTo().performClick()
        assertThat(shadowOf(app).nextStartedActivity.data).isEqualTo(Uri.parse("https://heycam.edgr.in/privacy/"))

        rule.onNodeWithText("Source code").performScrollTo().performClick()
        assertThat(shadowOf(app).nextStartedActivity.data).isEqualTo(Uri.parse("https://github.com/uroy80/heycam"))

        // TalkBack hears where each link goes: the browser, or the mail app for Contact.
        rule.onNode(hasText("Source code") and hasContentDescription("Opens in your browser")).assertExists()
        rule.onNode(hasText("Contact") and hasContentDescription("Opens your email app")).assertExists()
        rule.onNode(hasText("Contact") and hasText("support@edgr.in")).performScrollTo().performClick()
        val mail = shadowOf(app).nextStartedActivity
        assertThat(mail.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(mail.data).isEqualTo(Uri.parse("mailto:support@edgr.in"))
        assertThat(mail.extras).isNull()

        rule.onNodeWithText("Open-source licences").performScrollTo().performClick()
        assertThat(calls).containsExactly("licences")
    }

    @Test
    fun theNameplateIsReadAsTheName() {
        about()
        rule.onNode(isHeading() and hasContentDescription("heycam")).assertExists()
        // The printed smile is decorative: TalkBack reads the description, never the raw "heycam :)".
        rule.onNodeWithText("heycam :)").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("heycam")))
    }

    @Test
    fun licencesOpenInPlaceAndBackReturnsToTheList() {
        licences()
        rule.onNode(hasText("heycam") and hasText("Usham Roy")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Apache License", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNode(hasText("Google ML Kit") and hasText("ML Kit terms")).performScrollTo().performClick()
        assertThat(shadowOf(app).nextStartedActivity.data).isEqualTo(Uri.parse(OpenSourceLicences.ML_KIT_TERMS_URL))
        assertThat(calls).isEmpty()
    }

    @Test
    fun pdfBoxShowsItsNoticeAboveTheApacheLicence() {
        licences()
        rule.onNode(hasText("PDFBox-Android") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Apache License", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Apache PDFBox\nCopyright 2014 The Apache Software Foundation", substring = true)).assertExists()
        rule.onNode(hasText("Adobe Font Metrics (AFM) for PDF Core 14 Fonts", substring = true)).assertExists()
    }

    @Test
    fun sqliteLinksItsDedication() {
        licences()
        rule.onNode(hasText("SQLite") and hasText("Public domain")).performScrollTo().performClick()
        assertThat(shadowOf(app).nextStartedActivity.data).isEqualTo(Uri.parse("https://sqlite.org/copyright.html"))
    }

    @Test
    fun aMissingLicenceFileSaysSo() {
        val missing = OpenSourceComponent("Example", "Nobody", "MIT", LicenceText.Asset("licences/not-there.txt"))
        rule.setContent { HeycamTheme(ThemeMode.DayPaper) { LicencesScreen(onBack = {}, components = listOf(missing)) } }
        rule.onNodeWithText("Example").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Couldn't open")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun about() = rule.setContent {
        HeycamTheme(ThemeMode.DayPaper) { AboutScreen(onBack = { calls += "back" }, onOpenLicences = { calls += "licences" }) }
    }

    private fun licences() = rule.setContent { HeycamTheme(ThemeMode.DayPaper) { LicencesScreen(onBack = { calls += "back" }) } }
}
