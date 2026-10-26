package heycam.feature.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.model.LibraryStats
import heycam.feature.library.home.FolderSection
import heycam.feature.library.home.HomeCallbacks
import heycam.feature.library.home.HomeScreen
import heycam.feature.library.home.HomeUiState
import java.time.LocalDate
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens for Home. Record: gw :feature:library:recordRoborazziDebug; CI verifies with verifyRoborazziDebug.
 * Times are formatted in UTC so the goldens do not depend on the machine's time zone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LibraryScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val zone = TimeZone.getDefault()
    private val today = LocalDate.of(2026, 10, 20)
    private val full = HomeUiState(
        loaded = true,
        stats = LibraryStats(5, 12, 900_000),
        latest = summary(4, "Lease agreement, flat 4B", updated = 1_792_000, pages = 8),
        latestFolder = "Home & lease",
        pinned = listOf(summary(2, "Passport copy", updated = 1_791_000, pinned = true)),
        sections = listOf(
            FolderSection(folder(10, "Receipts", count = 2), listOf(summary(3, "Hardware store, paint and brushes", folder = 10, updated = 1_790_000, pages = 1))),
        ),
        documents = listOf(summary(1, "Electricity bill, September", updated = 1_789_000, pages = 3)),
    )

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private fun capture(name: String, state: HomeUiState, fontScale: Float = 1f) {
        rule.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                AtFontScale(fontScale) {
                    HomeScreen(state, today, toolsTab = { Text("Tools") }, scanStatus = {}, snackbar = SnackbarHostState(), callbacks = HomeCallbacks())
                }
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun home() = capture("home_day", full)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun firstRun() = capture("home_empty_day", HomeUiState(loaded = true))

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun homeAtDoubleFontSize() = capture("home_font200", full, fontScale = 2f)

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xhdpi")
    fun homeInLandscape() = capture("home_landscape", full)
}
