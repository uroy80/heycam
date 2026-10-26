package heycam.feature.scan.review

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import heycam.core.designsystem.HeycamTheme
import heycam.core.designsystem.ThemeMode
import heycam.core.model.ImportTarget
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedPage
import heycam.feature.scan.AtFontScale
import heycam.feature.scan.pagePicture
import heycam.feature.scan.stagedImage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/* Roborazzi goldens for Review. Record: gw :feature:scan:recordRoborazziDebug; CI verifies with verifyRoborazziDebug. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReviewScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val scanned = (1..3).map { StagedPage(stagedImage("p$it")) }.let { pages ->
        pages.mapIndexed { i, p -> if (i == 0) p.copy(edits = p.edits.copy(filter = PageFilter.AUTO)) else p }
    }

    private fun capture(name: String, pages: List<StagedPage>, pictureSize: Pair<Int, Int> = 248 to 351, fontScale: Float = 1f) {
        val state = ReviewUiState(loading = false, session = ScanSession("s1", ImportTarget.NewDocument(), pages, Instant.EPOCH))
        val picture = pagePicture(pictureSize.first, pictureSize.second)
        rule.setContent {
            HeycamTheme(ThemeMode.DayPaper) {
                AtFontScale(fontScale) { ReviewScreen(state, { _, _, _ -> picture }, SnackbarHostState(), ReviewCallbacks()) }
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun review() = capture("review_day", scanned)

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun reviewAtDoubleFontSize() = capture("review_font200", scanned, fontScale = 2f)

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xhdpi")
    fun reviewInLandscape() = capture("review_landscape", scanned)

    /** A 300 x 200 photo is drawn as large as the space allows, not at its pixel size. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun smallPhotoIsFitted() =
        capture("review_small_photo", listOf(StagedPage(stagedImage("small", PageOrigin.PHOTO_PICKER, w = 300, h = 200))), pictureSize = 300 to 200)
}
