package heycam.core.imaging

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.model.Adjustments
import heycam.core.model.PageFilter
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

/** Plain JVM tests: the filters touch no Android class. */
class DocumentPixelFiltersTest {
    private val filters = DocumentPixelFilters()
    private val page = SyntheticPage(width = 600, height = 800)

    private fun filtered(filter: PageFilter, source: SyntheticPage = page, adjust: Adjustments = Adjustments.NONE): IntArray =
        source.pixels().also { filters.apply(it, source.width, source.height, filter, adjust) }

    /** Share of the [source]'s ink (or paper) pixels that satisfy [test], ignoring the 2 px around each word edge. */
    private fun share(px: IntArray, source: SyntheticPage, ink: Boolean, test: (Int) -> Boolean, columns: IntRange = 0 until source.width): Double {
        var hits = 0
        var total = 0
        for (y in 2 until source.height - 2) {
            for (x in columns) {
                if (x < 2 || x >= source.width - 2) continue
                val solid = (-2..2).all { d -> source.isInk(x + d, y) == ink && source.isInk(x, y + d) == ink }
                if (!solid) continue
                total++
                if (test(px[y * source.width + x])) hits++
            }
        }
        return hits.toDouble() / total
    }

    @Test
    fun originalLeavesPixelsUntouched() {
        val px = filtered(PageFilter.ORIGINAL)
        assertThat(px).isEqualTo(page.pixels())
    }

    @Test
    fun blackWhiteGivesPureInkOnWhitePaperEvenInShadow() {
        val px = filtered(PageFilter.BLACK_WHITE)
        assertThat(px.all { it == -0x1000000 || it == -0x1 }).isTrue()
        val shadowSide = 0 until page.width / 3
        assertThat(share(px, page, ink = true, test = { it == -0x1000000 })).isAtLeast(0.98)
        assertThat(share(px, page, ink = false, test = { it == -0x1 })).isAtLeast(0.99)
        assertThat(share(px, page, ink = true, test = { it == -0x1000000 }, columns = shadowSide)).isAtLeast(0.98)
        assertThat(share(px, page, ink = false, test = { it == -0x1 }, columns = shadowSide)).isAtLeast(0.99)
    }

    /** AUTO and GRAYSCALE are global, like the plan says: they fix the paper colour and range, not uneven light. */
    private val evenlyLit = SyntheticPage(width = 600, height = 800, shadow = 0f)

    @Test
    fun autoWhitensTintedPaperAndKeepsInkDark() {
        val px = filtered(PageFilter.AUTO, evenlyLit)
        assertThat(share(px, evenlyLit, ink = false, test = { luma(it) > 235 && abs(red(it) - blue(it)) < 16 })).isAtLeast(0.95)
        assertThat(share(px, evenlyLit, ink = true, test = { luma(it) < 50 })).isAtLeast(0.95)
    }

    /** A colour photo whose brightest area is a yellow sky, not paper: AUTO must not "white-balance" it into new colours. */
    @Test
    fun autoKeepsTheHuesOfAColourPage() {
        val colours = listOf(rgb(220, 250, 110), rgb(160, 120, 110), rgb(85, 215, 120))   // yellow, brown, green
        val width = 300
        val band = 100
        val px = IntArray(width * band * colours.size) { i -> colours[i / width / band] }
        filters.apply(px, width, band * colours.size, PageFilter.AUTO)
        colours.forEachIndexed { i, before ->
            val after = px[(i * band + band / 2) * width + width / 2]
            val change = "${channels(before)} -> ${channels(after)}"
            assertWithMessage("channel order, $change").that(channelOrder(after)).isEqualTo(channelOrder(before))
            assertWithMessage("hue shift in degrees, $change").that(hueShift(hue(before), hue(after))).isLessThan(15.0)
        }
    }

    @Test
    fun castCorrectionFadesOutAsTheBrightestAreaGetsColourful() {
        assertThat(DocumentPixelFilters.castCorrection(205, 236)).isEqualTo(256)   // tinted paper: full white balance
        assertThat(DocumentPixelFilters.castCorrection(160, 256)).isEqualTo(128)   // halfway
        assertThat(DocumentPixelFilters.castCorrection(110, 250)).isEqualTo(0)     // yellow sky: none
        assertThat(DocumentPixelFilters.castCorrection(0, 0)).isEqualTo(256)       // black page: nothing to balance
    }

    @Test
    fun grayscaleIsNeutralAndStretched() {
        val px = filtered(PageFilter.GRAYSCALE, evenlyLit)
        assertThat(px.all { red(it) == green(it) && green(it) == blue(it) }).isTrue()
        assertThat(share(px, evenlyLit, ink = false, test = { luma(it) > 235 })).isAtLeast(0.95)
        assertThat(share(px, evenlyLit, ink = true, test = { luma(it) < 50 })).isAtLeast(0.95)
    }

    @Test
    fun blackWhiteKeepsLargeDarkAreasDark() {
        val width = 400
        val height = 400
        val px = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in 100 until 300 && y in 100 until 300) rgb(50, 50, 50) else rgb(235, 235, 230)
        }
        filters.apply(px, width, height, PageFilter.BLACK_WHITE)
        val inside = (110 until 290).flatMap { y -> (110 until 290).map { x -> px[y * width + x] } }
        assertThat(inside.count { it == -0x1000000 }.toDouble() / inside.size).isAtLeast(0.99)
        assertThat(px[10 * width + 10]).isEqualTo(-0x1)
    }

    @Test
    fun whiteboardWhitensBackgroundAndKeepsMarkerColour() {
        val board = SyntheticPage(width = 400, height = 300, paper = rgb(200, 205, 210), ink = rgb(30, 60, 170), shadow = 0.3f)
        val px = filtered(PageFilter.WHITEBOARD, board)
        assertThat(share(px, board, ink = false, test = { luma(it) > 225 })).isAtLeast(0.95)
        assertThat(share(px, board, ink = true, test = { blue(it) > red(it) + 60 })).isAtLeast(0.95)
    }

    @Test
    fun blankPageStaysCleanUnderEveryFilter() {
        val blank = SyntheticPage(width = 300, height = 400, paper = rgb(242, 242, 240), shadow = 0f, noise = 3, withText = false)
        for (filter in listOf(PageFilter.AUTO, PageFilter.GRAYSCALE, PageFilter.BLACK_WHITE, PageFilter.WHITEBOARD)) {
            val px = filtered(filter, blank)
            assertWithMessage("$filter darkest pixel").that(px.minOf { luma(it) }).isAtLeast(200)
        }
        assertThat(filtered(PageFilter.BLACK_WHITE, blank).all { it == -0x1 }).isTrue()
    }

    @Test
    fun darkPhotoIsNotStretchedIntoNoise() {
        val dark = SyntheticPage(width = 200, height = 200, paper = rgb(40, 40, 40), shadow = 0f, noise = 4, withText = false)
        for (filter in listOf(PageFilter.AUTO, PageFilter.GRAYSCALE)) {
            val px = filtered(filter, dark)
            // Gain is capped at 2x, so a +-4 noise band can spread to at most about +-8 (plus rounding).
            val spread = px.maxOf { luma(it) } - px.minOf { luma(it) }
            assertWithMessage("$filter spread").that(spread).isAtMost(20)
        }
    }

    @Test
    fun adjustmentsMoveBrightnessAndContrast() {
        val flat = IntArray(4) { rgb(100, 150, 200) }
        filters.apply(flat, 2, 2, PageFilter.ORIGINAL, Adjustments(brightness = 0.25f))
        assertThat(flat.toSet()).containsExactly(rgb(132, 182, 232))

        val noContrast = IntArray(4) { rgb(10, 128, 250) }
        filters.apply(noContrast, 2, 2, PageFilter.ORIGINAL, Adjustments(contrast = -1f))
        assertThat(noContrast.toSet()).containsExactly(rgb(128, 128, 128))
    }

    @Test
    fun alphaIsKeptByColourFilters() {
        val px = IntArray(4) { (0x80 shl 24) or 0x00406080 }
        filters.apply(px, 2, 2, PageFilter.GRAYSCALE)
        assertThat(px.all { alpha(it) == 0x80 }).isTrue()
    }

    @Test
    fun outputIsDeterministic() {
        for (filter in PageFilter.entries) {
            assertWithMessage(filter.name).that(filtered(filter)).isEqualTo(filtered(filter))
        }
    }

    /**
     * Golden summaries of each filter on the standard synthetic page, with a small tolerance. They change only when a
     * filter is changed on purpose; then re-measure docs/export-presets.txt too, since file sizes follow the filters.
     */
    @Test
    fun goldenSummaries() {
        val golden = mapOf(
            PageFilter.AUTO to Summary(meanLuma = 160.696, darkShare = 0.19501),
            PageFilter.GRAYSCALE to Summary(meanLuma = 157.897, darkShare = 0.19979),
            PageFilter.BLACK_WHITE to Summary(meanLuma = 205.736, darkShare = 0.19319),
            PageFilter.WHITEBOARD to Summary(meanLuma = 203.211, darkShare = 0.19319),
        )
        for ((filter, expected) in golden) {
            val actual = Summary.of(filtered(filter))
            assertWithMessage("$filter mean luma, actual $actual").that(actual.meanLuma).isWithin(0.5).of(expected.meanLuma)
            assertWithMessage("$filter dark share, actual $actual").that(actual.darkShare).isWithin(0.002).of(expected.darkShare)
        }
    }

    @Test
    fun rejectsMismatchedSizes() {
        assertThrows(IllegalArgumentException::class.java) { filters.apply(IntArray(5), 2, 2, PageFilter.AUTO) }
        assertThrows(IllegalArgumentException::class.java) { filters.apply(IntArray(0), 0, 0, PageFilter.AUTO) }
    }

    @Test
    fun percentileAndStretchHelpers() {
        val histogram = IntArray(256).apply { this[10] = 1; this[100] = 98; this[250] = 1 }
        assertThat(DocumentPixelFilters.percentile(histogram, 100, 0.01)).isEqualTo(10)
        assertThat(DocumentPixelFilters.percentile(histogram, 100, 0.99)).isEqualTo(100)
        assertThat(DocumentPixelFilters.percentile(histogram, 100, 1.0)).isEqualTo(250)
        val lut = DocumentPixelFilters.stretch(20, 220)
        assertThat(lut[20]).isEqualTo(0)
        assertThat(lut[220]).isEqualTo(255)
        assertThat(lut[120]).isEqualTo(128)
        // A narrow span is widened downwards: the top still reaches white, the gain stays at 2x or less.
        val narrow = DocumentPixelFilters.stretch(230, 240)
        assertThat(narrow[240]).isEqualTo(255)
        assertThat(narrow[230] - narrow[229]).isAtMost(2)
    }

    private fun channels(c: Int) = "(${red(c)}, ${green(c)}, ${blue(c)})"

    /** Channel names from strongest to weakest. */
    private fun channelOrder(c: Int) = listOf("R" to red(c), "G" to green(c), "B" to blue(c)).sortedByDescending { it.second }.map { it.first }

    /** HSV hue in degrees; the colours tested here all have a hue. */
    private fun hue(c: Int): Double {
        val r = red(c).toDouble()
        val g = green(c).toDouble()
        val b = blue(c).toDouble()
        val max = maxOf(r, g, b)
        val range = max - minOf(r, g, b)
        require(range > 0) { "grey has no hue" }
        val h = when (max) {
            r -> 60 * (g - b) / range
            g -> 60 * ((b - r) / range + 2)
            else -> 60 * ((r - g) / range + 4)
        }
        return (h + 360) % 360
    }

    private fun hueShift(a: Double, b: Double) = abs(a - b).let { minOf(it, 360 - it) }

    private data class Summary(val meanLuma: Double, val darkShare: Double) {
        companion object {
            fun of(px: IntArray) = Summary(
                meanLuma = px.sumOf { luma(it).toLong() }.toDouble() / px.size,
                darkShare = px.count { luma(it) < 128 }.toDouble() / px.size,
            )
        }
    }
}
