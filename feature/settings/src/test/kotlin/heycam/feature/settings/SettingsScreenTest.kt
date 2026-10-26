package heycam.feature.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.designsystem.showHeycamSnackbar
import heycam.core.model.ExportOptions
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.Settings
import heycam.core.model.SizePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val loaded = SettingsUiState(settings = Settings(), spaceUsedBytes = 2_480_000)
    private val snackbar = SnackbarHostState()
    private lateinit var scope: CoroutineScope

    private fun show(initial: SettingsUiState, windowInsets: WindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)) {
        var state by mutableStateOf(initial)
        fun save(transform: (Settings) -> Settings) {
            state = state.copy(settings = transform(state.settings!!))
        }
        rule.setContent {
            scope = rememberCoroutineScope()
            HeycamTheme(ThemeMode.DayPaper) {
                SettingsScreen(
                    state = state,
                    snackbar = snackbar,
                    windowInsets = windowInsets,
                    actions = SettingsActions(
                        onBack = { calls += "back" },
                        onTheme = { calls += "theme $it"; save { s -> s.copy(theme = it) } },
                        onOcrScript = { calls += "script $it"; save { s -> s.copy(ocrScript = it) } },
                        onAutoText = { calls += "autoText $it"; save { s -> s.copy(autoText = it) } },
                        onDefaultFilter = { calls += "filter $it"; save { s -> s.copy(defaultFilter = it) } },
                        onDownloadModels = { calls += "models" },
                        onExportSize = { calls += "size $it" },
                        onExportPageSize = { calls += "page $it" },
                        onExportMargin = { calls += "margin $it" },
                        onExportSearchable = { calls += "searchable $it"; save { s -> s.copy(exportDefaults = s.exportDefaults.copy(searchable = it)) } },
                        onOpenStorage = { calls += "storage" },
                        onOpenAbout = { calls += "about" },
                    ),
                )
            }
        }
    }

    private fun isRadio(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun radio(label: String) = rule.onNode(isRadio(label))

    private val models = hasText("Download all languages") and hasClickAction()

    @Test
    fun groupsAreHeadings() {
        show(loaded)
        listOf("Appearance", "Scanning", "Export", "Privacy", "Storage", "About").forEach { group ->
            rule.onNode(isHeading() and hasContentDescription(group)).performScrollTo().assertIsDisplayed()
        }
        rule.onNode(isHeading() and hasText("Settings")).assertIsDisplayed()
    }

    @Test
    fun themeIsARadioGroup() {
        show(loaded)
        radio("System").assertIsSelected()
        radio("Night ink").performClick()
        radio("Night ink").assertIsSelected()
        radio("System").assertIsNotSelected()
        assertThat(calls).containsExactly("theme NIGHT_INK")
    }

    @Test
    fun scanningChoices() {
        show(loaded)
        rule.onAllNodes(isRadio("Original"))[0].assertIsSelected() // the filter; the size row has one too
        radio("B&W").performClick()
        rule.onNodeWithText("Read text automatically").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertThat(calls).containsExactly("filter BLACK_WHITE", "autoText false").inOrder()
    }

    @Test
    fun textLanguageIsPickedFromADialog() {
        show(loaded)
        rule.onNode(hasText("Text language") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(isRadio("Auto") and hasText("From your phone's languages")).assertIsSelected()
        rule.onNode(hasText("Devanagari") and hasText("हिन्दी, मराठी, नेपाली")).performClick().assertIsSelected()
        rule.onNode(isRadio("Auto") and hasText("From your phone's languages")).assertIsNotSelected()
        assertThat(calls).isEmpty()

        rule.onNodeWithText("OK").performClick()
        assertThat(calls).containsExactly("script DEVANAGARI")
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        rule.onNode(hasText("Text language") and hasText("Devanagari")).assertExists()
    }

    @Test
    fun cancellingTheLanguageKeepsIt() {
        show(loaded)
        rule.onNode(hasText("Text language") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasText("Korean")).performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertThat(calls).isEmpty()
        rule.onNode(hasText("Text language") and hasText("Auto")).assertExists()
    }

    @Test
    fun exportDefaults() {
        show(loaded.copy(settings = Settings(exportDefaults = ExportOptions(size = SizePreset.SMALL, pageSize = PageSize.A4, margin = Margin.NARROW))))
        radio("Small").performScrollTo().assertIsSelected()
        radio("A4").assertIsSelected()
        radio("Narrow").assertIsSelected()
        radio("Legal").performScrollTo().performClick()
        radio("Normal").performScrollTo().performClick()
        rule.onNodeWithText("Searchable PDF").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertThat(calls).containsExactly("page LEGAL", "margin NORMAL", "searchable false").inOrder()
    }

    @Test
    fun yourDataIsPlainAndLinksThePolicy() {
        show(loaded)
        rule.onNodeWithText("Your data").performScrollTo().performClick()
        rule.onNodeWithText("Scans stay on this phone. No account, no ads.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Google ML Kit sends usage diagnostics to Google.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Privacy policy").performClick()
        val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data).isEqualTo(Uri.parse("https://heycam.edgr.in/privacy/"))
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Privacy policy").assertDoesNotExist()
    }

    @Test
    fun storageAndAboutOpenTheirScreens() {
        show(loaded)
        rule.onNode(hasText("Space used") and hasText("2.5 MB")).performScrollTo().performClick()
        rule.onNodeWithText("About heycam").performScrollTo().performClick()
        assertThat(calls).containsExactly("storage", "about").inOrder()
    }

    @Test
    fun modelsRowShowsADownloadMarkWhenIdle() {
        show(loaded)
        rule.onNode(models).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag(MODELS_DOWNLOAD_ICON, useUnmergedTree = true).assertIsDisplayed()
        assertThat(calls).containsExactly("models")
    }

    @Test
    fun modelsRowFollowsTheDownloadQuietly() {
        show(loaded.copy(models = ModelsDownload.Running(percent = 42)))
        rule.onNode(models and hasText("42%")).performScrollTo().assertIsNotEnabled().assert(isNotLive)
        rule.onNodeWithText("42%", useUnmergedTree = true).assert(isNotLive)
        rule.onNode(hasProgress(0.42f), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(MODELS_DOWNLOAD_ICON, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun modelsStartAndEndAreAnnounced() {
        show(loaded.copy(models = ModelsDownload.Running(percent = null)))
        rule.onNodeWithText("Downloading…", useUnmergedTree = true).performScrollTo().assert(isLive)
        rule.onNodeWithTag(MODELS_DOWNLOAD_ICON, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun modelsReady() {
        show(loaded.copy(models = ModelsDownload.Ready))
        rule.onNodeWithText("Ready", useUnmergedTree = true).performScrollTo().assert(isLive)
    }

    @Test
    fun modelsOffline() {
        show(loaded.copy(models = ModelsDownload.Failed(offline = true)))
        rule.onNode(models and hasText("No connection")).performScrollTo().performClick()
        rule.onNodeWithText("No connection", useUnmergedTree = true).assert(isLive)
        assertThat(calls).containsExactly("models")
    }

    @Test
    fun modelsFailed() {
        show(loaded.copy(models = ModelsDownload.Failed(offline = false)))
        rule.onNode(models and hasText("Couldn't download. Tap to retry.")).performScrollTo().assertIsEnabled()
    }

    @Test
    fun modelsUnsupported() {
        show(loaded.copy(models = ModelsDownload.Unsupported))
        rule.onNode(hasText("Download all languages") and hasText("Not available on this phone")).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun loadingShowsNoChoices() {
        show(SettingsUiState())
        rule.onNodeWithText("Theme").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()
        assertThat(calls).containsExactly("back")
    }

    @Test
    fun thePageClearsTheSystemBars() {
        val top = 40.dp
        val bottom = 60.dp
        show(loaded, WindowInsets(0.dp, top, 0.dp, bottom))
        val screen = rule.onRoot().getUnclippedBoundsInRoot()
        assertThat(rule.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot().top).isAtLeast(top)

        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        assertThat(rule.onNodeWithText("About heycam").getUnclippedBoundsInRoot().bottom).isAtMost(screen.bottom - bottom)

        rule.runOnUiThread { scope.launch { snackbar.showHeycamSnackbar("Couldn't save", isError = true) } }
        assertThat(rule.onNodeWithText("Couldn't save").getUnclippedBoundsInRoot().bottom).isAtMost(screen.bottom - bottom)
    }

    private val isLive = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    private val isNotLive = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

    private fun hasProgress(value: Float) = SemanticsMatcher("progress $value") { node ->
        node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == value
    }
}
