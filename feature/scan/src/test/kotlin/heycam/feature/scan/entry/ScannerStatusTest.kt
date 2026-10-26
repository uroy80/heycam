package heycam.feature.scan.entry

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.UnsupportedReason
import heycam.feature.scan.ScanMessage
import heycam.feature.scan.text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ScannerStatusTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: ModuleState?, onRetry: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        rule.setContent { MaterialTheme { ScannerStatusContent(state, onRetry, onDismiss) } }
    }

    @Test
    fun downloadShowsProgress() {
        show(ModuleState.Downloading(bytesDownloaded = 40, totalBytes = 100))
        rule.onNodeWithContentDescription("Getting the scanner").assertIsDisplayed()
        rule.onNodeWithText("One time · needs internet").assertIsDisplayed()
        rule.onNodeWithText("40%").assertIsDisplayed()
    }

    @Test
    fun failureOffersRetryAndDismiss() {
        var retried = false
        var dismissed = false
        show(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true), { retried = true }, { dismissed = true })
        rule.onNodeWithText("No connection").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertThat(retried).isTrue()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun unsupportedPhoneSaysWhy() {
        show(ModuleState.Unsupported(UnsupportedReason.PLAY_SERVICES_OUTDATED))
        rule.onNodeWithText("Update Google Play services to scan").assertIsDisplayed()
    }

    @Test
    fun readyShowsNothing() {
        show(ModuleState.Ready)
        rule.onRoot().onChildren().assertCountEquals(0)
    }

    @Test
    fun messagesHaveShortText() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertThat(ScanMessage.Rejected(2).text(resources)).isEqualTo("Couldn't add 2 images")
        assertThat(EntryNotice.Unavailable(UnsupportedReason.NO_PLAY_SERVICES).toMessage(resources))
            .isEqualTo(ScanMessage.ScannerUnavailable("Scanning needs Google Play services"))
        assertThat(EntryNotice.Failed.toMessage(resources).text(resources)).isEqualTo("Couldn't do that. Try again.")
    }
}
