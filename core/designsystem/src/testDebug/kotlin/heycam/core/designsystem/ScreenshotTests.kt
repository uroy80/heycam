package heycam.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.catalog.AboutSketch
import heycam.core.designsystem.catalog.ControlsSheet
import heycam.core.designsystem.catalog.FeedbackSheet
import heycam.core.designsystem.catalog.LicencesSketch
import heycam.core.designsystem.catalog.PageViewerSketch
import heycam.core.designsystem.catalog.PagesSheet
import heycam.core.designsystem.catalog.RowsSheet
import heycam.core.designsystem.catalog.ScannerDownloadSketch
import heycam.core.designsystem.catalog.SearchSketch
import heycam.core.designsystem.catalog.StorageSketch
import heycam.core.designsystem.catalog.TrashSketch
import heycam.core.designsystem.catalog.TypeSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens (PLAN 6.3, gate G4) on Robolectric native graphics, in Day paper and Night ink.
 * Record: ./gradlew :core:designsystem:recordRoborazziDebug   Verify (CI): ./gradlew :core:designsystem:verifyRoborazziDebug
 * Goldens live in src/test/screenshots; review them against research/design/png when the design changes.
 */

private const val GOLDENS = "src/test/screenshots"
private val modes = listOf(ThemeMode.DayPaper, ThemeMode.NightInk)

/** The component catalog, one sheet per golden, measured to its own height at a 390 dp phone width. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h2400dp-xhdpi")
class CatalogScreenshotTest(private val sheet: String, private val mode: ThemeMode) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sheet() {
        compose.setContent {
            HeycamTheme(mode) { Box(Modifier.testTag("sheet")) { sheets.getValue(sheet)() } }
        }
        compose.onNodeWithTag("sheet").captureRoboImage("$GOLDENS/catalog/${sheet}_${mode.file}.png")
    }

    companion object {
        private val sheets: Map<String, @Composable () -> Unit> = mapOf(
            "type" to { TypeSheet() },
            "controls" to { ControlsSheet() },
            "rows" to { RowsSheet() },
            "pages" to { PagesSheet() },
            "feedback" to { FeedbackSheet() },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun cases() = sheets.keys.flatMap { s -> modes.map { arrayOf<Any>(s, it) } }
    }
}

/** The screens without HTML mockups (PLAN 5), at the 390 x 844 dp phone size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ScreenSketchScreenshotTest(private val screen: String, private val mode: ThemeMode) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screen() {
        compose.setContent {
            HeycamTheme(mode) { Box(Modifier.testTag("screen")) { screens.getValue(screen)() } }
        }
        compose.onNodeWithTag("screen").captureRoboImage("$GOLDENS/screens/${screen}_${mode.file}.png")
    }

    companion object {
        internal val screens: Map<String, @Composable () -> Unit> = mapOf(
            "s3_search" to { SearchSketch() },
            "s5_trash" to { TrashSketch() },
            "s6_scanner_download" to { ScannerDownloadSketch() },
            "s6_scanner_failed" to { ScannerDownloadSketch(failed = true) },
            "s9_page_viewer" to { PageViewerSketch() },
            "s14_about" to { AboutSketch() },
            "s14_licences" to { LicencesSketch() },
            "s14_storage" to { StorageSketch() },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun cases() = screens.keys.flatMap { s -> modes.map { arrayOf<Any>(s, it) } }
    }
}

/** Text at 200 %: the screens reflow and only the nameplate stays capped (PLAN 5, platform rules). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class LargeTextScreenshotTest(private val screen: String) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screen() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                Box(Modifier.testTag("screen")) { ScreenSketchScreenshotTest.screens.getValue(screen)() }
            }
        }
        compose.onNodeWithTag("screen").captureRoboImage("$GOLDENS/screens/${screen}_font200.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf("s6_scanner_download", "s14_storage").map { arrayOf<Any>(it) }
    }
}

private val ThemeMode.file get() = if (this == ThemeMode.NightInk) "night" else "day"
