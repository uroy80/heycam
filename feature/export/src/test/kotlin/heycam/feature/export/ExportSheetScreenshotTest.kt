package heycam.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.model.DocumentId
import heycam.core.model.SizePreset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of the export sheet over the dialog's dim (PLAN 6.3): both editions, and Day paper right to left,
 * where the segmented rows must keep their outer edges.
 * Record: :feature:export:recordRoborazziDebug   Verify (CI): :feature:export:verifyRoborazziDebug
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h900dp-xhdpi")
class ExportSheetScreenshotTest(private val name: String, private val mode: ThemeMode, private val direction: LayoutDirection) {
    @get:Rule val compose = createComposeRule()

    private val state = ExportUiState(
        loading = false,
        documents = listOf(ExportDocument(DocumentId(1), "Lease agreement, flat 4B", pageCount = 8, pendingTextPages = 0)),
        fileName = "Lease agreement, flat 4B",
        estimates = mapOf(SizePreset.SMALL to 600_000L, SizePreset.STANDARD to 1_800_000L, SizePreset.ORIGINAL to 9_400_000L),
    )

    @Test
    fun sheet() {
        compose.setContent {
            HeycamTheme(mode) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Heycam.colors.paper)
                            .background(Heycam.colors.scrim)
                            .testTag("screen"),
                    ) {
                        ExportSheet(
                            state = state,
                            onFormat = {}, onSize = {}, onPageSize = {}, onMargin = {}, onSearchable = {}, onFileName = {},
                            onSave = {}, onShare = {}, onPrint = {}, onTextPendingAnswer = {}, onTextPendingDismiss = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("screen").captureRoboImage("src/test/screenshots/export_sheet_$name.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf<Any>("day", ThemeMode.DayPaper, LayoutDirection.Ltr),
            arrayOf<Any>("night", ThemeMode.NightInk, LayoutDirection.Ltr),
            arrayOf<Any>("day_rtl", ThemeMode.DayPaper, LayoutDirection.Rtl),
        )
    }
}
