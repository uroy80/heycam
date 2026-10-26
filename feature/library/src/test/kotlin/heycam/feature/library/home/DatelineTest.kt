package heycam.feature.library.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.model.LibraryStats
import heycam.feature.library.AtFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dateline never clips: not at 200% text, where its two labels used to run together ("ON THIS PHONEMON, OCT"),
 * and not on a narrow phone, where the counts used to lose their unit ("2 DOCS · 5").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths; the legacy shadows measure a character as 1 px
class DatelineTest {
    @get:Rule val rule = createComposeRule()

    private val layouts = mutableListOf<TextLayoutResult>()
    private var counts: String? = null

    private fun show(stats: LibraryStats, fontScale: Float = 1f) {
        rule.setContent {
            MaterialTheme {
                AtFontScale(fontScale) {
                    Dateline(
                        stats,
                        date = "Mon, Oct 26",
                        spokenDate = "Monday, October 26",
                        onTextLayout = { layouts += it },
                        onCounts = { counts = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertNothingClipped() {
        assertThat(layouts).isNotEmpty()
        layouts.forEach { layout ->
            assertWithMessage(layout.layoutInput.text.text).that(layout.hasVisualOverflow).isFalse()
        }
        val date = layouts.last { it.layoutInput.text.text == "MON, OCT 26" }
        assertThat(date.getLineEnd(date.lineCount - 1)).isEqualTo("MON, OCT 26".length)
    }

    @Test
    @Config(qualifiers = "w308dp-h640dp")
    fun aNarrowPhoneShowsWholeCountsOrFewer() {
        show(LibraryStats(2, 5, 1_000))
        assertNothingClipped()
        assertThat(counts).isAnyOf("2 DOCS · 5 PP", "2 DOCS")
    }

    @Test
    @Config(qualifiers = "w308dp-h640dp")
    fun countsThatCannotFitDropOutRatherThanBreak() {
        show(LibraryStats(1_234_567, 98_765_432, 1_000))
        assertNothingClipped()
        assertThat(counts).isAnyOf("1234567 DOCS", null)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aRoomyPhoneShowsEverything() {
        show(LibraryStats(42, 318, 1_000))
        assertNothingClipped()
        assertThat(counts).isEqualTo("42 DOCS · 318 PP")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun doubleSizeTextWrapsBothLabelsInsteadOfJoiningThem() {
        show(LibraryStats(2, 5, 1_000), fontScale = 2f)
        assertNothingClipped()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun doubleSizeTextPutsThePlaceAndTheDateOnLinesOfTheirOwn() {
        // On a wide phone both would fit on one line, where they read as one run of caps.
        show(LibraryStats(2, 5, 1_000), fontScale = 2f)
        assertNothingClipped()
        val place = layouts.last { it.layoutInput.text.text == "ON THIS PHONE" }.size.height
        val date = layouts.last { it.layoutInput.text.text == "MON, OCT 26" }.size.height
        val line = rule.onNodeWithContentDescription("On this phone", substring = true).fetchSemanticsNode().size.height
        assertThat(line).isAtLeast(place + date)
    }

    @Test
    @Config(qualifiers = "w308dp-h640dp")
    fun largestFontOnTheNarrowPhone() {
        show(LibraryStats(2, 5, 1_000), fontScale = 1.3f)
        assertNothingClipped()
    }
}
