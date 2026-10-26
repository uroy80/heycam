package heycam.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.data.StorageUsage
import heycam.core.model.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the settings screens (PLAN 6.3), in both editions. The page is tall enough to show every row.
 * Record: :feature:settings:recordRoborazziDebug   Verify (CI): :feature:settings:verifyRoborazziDebug
 */

private const val GOLDENS = "src/test/screenshots"
private val noInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)

private val settingsActions = SettingsActions(
    onBack = {}, onTheme = {}, onOcrScript = {}, onAutoText = {}, onDefaultFilter = {}, onDownloadModels = {},
    onExportSize = {}, onExportPageSize = {}, onExportMargin = {}, onExportSearchable = {}, onOpenStorage = {}, onOpenAbout = {},
)

@Composable
private fun SettingsSample(windowInsets: WindowInsets) = SettingsScreen(
    state = SettingsUiState(settings = Settings(), spaceUsedBytes = 182_000_000),
    actions = settingsActions,
    snackbar = SnackbarHostState(),
    windowInsets = windowInsets,
)

private val modes = listOf(ThemeMode.DayPaper, ThemeMode.NightInk)

/** Settings on a page tall enough to show every row. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h1360dp-xhdpi")
class SettingsScreenshotTest(private val mode: ThemeMode) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settings() {
        compose.setContent {
            HeycamTheme(mode) { Box(Modifier.testTag("screen")) { SettingsSample(noInsets) } }
        }
        compose.onNodeWithTag("screen").captureRoboImage("$GOLDENS/settings_${mode.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = modes.map { arrayOf<Any>(it) }
    }
}

/** Storage, About and the licence list at the 390 x 844 dp phone size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
class SettingsPagesScreenshotTest(private val screen: String, private val mode: ThemeMode) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screen() {
        compose.setContent {
            HeycamTheme(mode) { Box(Modifier.testTag("screen")) { screens.getValue(screen)() } }
        }
        compose.onNodeWithTag("screen").captureRoboImage("$GOLDENS/${screen}_${mode.name.lowercase()}.png")
    }

    companion object {
        private val screens: Map<String, @Composable () -> Unit> = mapOf(
            "storage" to {
                StorageScreen(
                    state = StorageUiState(usage = StorageUsage(182_000_000, 12_000_000, 3_100_000), trashCount = 4),
                    onBack = {}, onEmptyTrash = {}, onClearTemporaryFiles = {}, onOpenTrash = {},
                    snackbar = SnackbarHostState(),
                    windowInsets = noInsets,
                )
            },
            "about" to { AboutScreen(onBack = {}, onOpenLicences = {}) },
            "licences" to { LicencesScreen(onBack = {}) },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun cases() = screens.keys.flatMap { s -> modes.map { arrayOf<Any>(s, it) } }
    }
}

/**
 * Text at 200 % under a 24 dp status bar and a 48 dp 3-button bar: the title stays below the clock's band and the
 * rows scroll between the two.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
class SettingsLargeTextScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsAtTwiceTheTextSize() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                Box(Modifier.testTag("screen")) { SettingsSample(WindowInsets(0.dp, 24.dp, 0.dp, 48.dp)) }
            }
        }
        compose.onNodeWithTag("screen").captureRoboImage("$GOLDENS/settings_font200.png")
    }
}
