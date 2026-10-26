package heycam.core.pdf

import com.google.common.truth.Truth.assertThat
import heycam.core.model.BoxPx
import heycam.core.model.TextLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class TextLayerTest {
    private val page = Placement(pageWidth = 500f, pageHeight = 1000f, imageX = 0f, imageY = 0f, imageWidth = 500f, imageHeight = 1000f)

    @Test
    fun straightLineSitsOnItsBoxBottom() {
        val placed = TextLayer.place(TextLine("Invoice", BoxPx(100f, 200f, 400f, 240f)), 1000, 2000, page)!!
        assertThat(placed.x).isWithin(0.001f).of(50f)
        assertThat(placed.y).isWithin(0.001f).of((2000f - 240f) / 2f)
        assertThat(placed.width).isWithin(0.001f).of(150f)
        assertThat(placed.size).isWithin(0.001f).of(20f)
        assertThat(placed.cos).isEqualTo(1f)
        assertThat(placed.sin).isZero()
    }

    @Test
    fun horizontalScaleStretchesTheLineToItsBox() {
        // 10 half-em glyphs at 20 pt are 100 pt wide unscaled.
        assertThat(TextLayer.horizontalScale(widthPt = 150f, sizePt = 20f, ems = 5f)).isWithin(0.001f).of(150f)
        assertThat(TextLayer.horizontalScale(widthPt = 50f, sizePt = 20f, ems = 5f)).isWithin(0.001f).of(50f)
    }

    @Test
    fun rotatedLineRecoversItsOwnRectangle() {
        val w = 300.0
        val h = 30.0
        val angle = 12.0
        val t = Math.toRadians(angle)
        val c = cos(t)
        val s = sin(t)
        // A w×h rectangle with its top-left at (100, 100), turned clockwise by 12° in image space (y down).
        val p0x = 100.0
        val p0y = 100.0
        val xs = listOf(p0x, p0x + w * c, p0x - h * s, p0x + w * c - h * s)
        val ys = listOf(p0y, p0y + w * s, p0y + h * c, p0y + w * s + h * c)
        val box = BoxPx(xs.min().toFloat(), ys.min().toFloat(), xs.max().toFloat(), ys.max().toFloat())
        val rect = TextLayer.lineRect(box, angle.toFloat())
        assertThat(rect.width.toDouble()).isWithin(0.01).of(w)
        assertThat(rect.height.toDouble()).isWithin(0.01).of(h)
        // Baseline start = bottom-left corner = p0 + h·(-sin, cos).
        assertThat(rect.baselineX.toDouble()).isWithin(0.01).of(p0x - h * s)
        assertThat(rect.baselineY.toDouble()).isWithin(0.01).of(p0y + h * c)
    }

    @Test
    fun rotationIsMirroredOntoThePdfsUpwardYAxis() {
        // 150 × 10 pt at 10°: too steep to level (see level), so the line keeps its own angle.
        val placed = TextLayer.place(TextLine("Tilted", rotatedBox(0f, 0f, 300f, 20f, 10f), angleDeg = 10f), 1000, 2000, page)!!
        assertThat(placed.sin).isLessThan(0f) // clockwise on screen = clockwise on paper = negative angle in PDF space
        assertThat(placed.cos.toDouble()).isWithin(0.0001).of(cos(Math.toRadians(10.0)))
    }

    @Test
    fun gentlyTiltedLineIsLevelledAboutItsCentre() {
        // 400 pt long, 20 pt high, 3° clockwise: its baseline falls 20.9 pt, more than poppler keeps in one line.
        val line = tilted(width = 400f, size = 20f, degrees = 3.0)
        val levelled = TextLayer.level(line)
        assertThat(abs(levelled.width * levelled.sin)).isWithin(0.01f).of(TextLayer.MAX_RISE * 20f)
        assertThat(levelled.sin).isLessThan(0f)
        assertThat(levelled.cos * levelled.cos + levelled.sin * levelled.sin).isWithin(0.0001f).of(1f)
        assertThat(centre(levelled).first).isWithin(0.01f).of(centre(line).first)
        assertThat(centre(levelled).second).isWithin(0.01f).of(centre(line).second)
        // Each end moves by half of what was taken off the rise, well under MAX_END_SHIFT × size.
        val endShift = abs(levelled.y - line.y)
        assertThat(endShift).isWithin(0.05f).of((abs(line.width * line.sin) - TextLayer.MAX_RISE * 20f) / 2f)
        assertThat(endShift).isAtMost(TextLayer.MAX_END_SHIFT * 20f)
    }

    @Test
    fun aTiltWithinTheRiseIsKept() {
        val short = tilted(width = 100f, size = 20f, degrees = 3.0) // falls 5.2 pt of the 8 allowed
        assertThat(TextLayer.level(short)).isEqualTo(short)
        val straight = PlacedLine("Flat", x = 10f, y = 20f, width = 400f, size = 20f, cos = 1f, sin = 0f)
        assertThat(TextLayer.level(straight)).isEqualTo(straight)
    }

    @Test
    fun aSteepLineKeepsItsAngle() {
        // Levelling a 10° line would move its ends by 31 pt, more than MAX_END_SHIFT × 20 pt.
        val steep = tilted(width = 400f, size = 20f, degrees = 10.0)
        assertThat(TextLayer.level(steep)).isEqualTo(steep)
    }

    @Test
    fun placeLevelsAGentlyTiltedLine() {
        // 1000 × 40 px at 2.5° on a 1:2 page: 500 × 20 pt, falling 21.8 pt before levelling.
        val box = rotatedBox(100f, 500f, 1000f, 40f, 2.5f)
        val placed = TextLayer.place(TextLine("A long tilted line of text", box, angleDeg = 2.5f), 1000, 2000, page)!!
        assertThat(abs(placed.width * placed.sin)).isWithin(0.05f).of(TextLayer.MAX_RISE * placed.size)
        assertThat(placed.sin).isLessThan(0f)
    }

    @Test
    fun nearDiagonalAndDegenerateBoxesFallBackToTheBox() {
        val box = BoxPx(0f, 0f, 100f, 100f)
        val rect = TextLayer.lineRect(box, 45f)
        assertThat(rect.width).isEqualTo(100f)
        assertThat(rect.cos).isEqualTo(1f)
        assertThat(TextLayer.place(TextLine("x", BoxPx(5f, 5f, 5f, 9f)), 10, 10, page)).isNull()
        assertThat(TextLayer.place(TextLine(" \u0007 ", BoxPx(0f, 0f, 5f, 5f)), 10, 10, page)).isNull()
    }

    private fun tilted(width: Float, size: Float, degrees: Double): PlacedLine {
        val t = Math.toRadians(-degrees) // clockwise on the scan = negative in PDF space
        return PlacedLine("Tilted", x = 50f, y = 600f, width = width, size = size, cos = cos(t).toFloat(), sin = sin(t).toFloat())
    }

    private fun centre(line: PlacedLine) = (line.x + line.cos * line.width / 2f) to (line.y + line.sin * line.width / 2f)

    @Test
    fun cleanNormalisesToNfcAndDropsControlCharacters() {
        assertThat(TextLayer.clean("Café\tbar\u0000")).isEqualTo("Café bar")
    }

    @Test
    fun contentStreamUsesInvisibleTextAndHexCids() {
        val cids = CidAllocator()
        val line = PlacedLine("ab", x = 10f, y = 20f, width = 30f, size = 12f, cos = 1f, sin = 0f)
        val content = TextLayer.content(listOf(line), cids)
        assertThat(content).startsWith("BT\n3 Tr\n")
        assertThat(content).contains("/F1 12 Tf\n")
        assertThat(content).contains("250 Tz\n")
        assertThat(content).contains("1 0 0 1 10 20 Tm\n<00010002> Tj\n")
        assertThat(content).endsWith("ET\n")
        assertThat(TextLayer.content(emptyList(), cids)).isEmpty()
    }

    @Test
    fun numbersAreShortAndNeverNegativeZero() {
        assertThat(TextLayer.num(1.5f)).isEqualTo("1.5")
        assertThat(TextLayer.num(2f)).isEqualTo("2")
        assertThat(TextLayer.num(-0.0001f)).isEqualTo("0")
        assertThat(TextLayer.num(0.12345f)).isEqualTo("0.123")
        assertThat(abs(TextLayer.num(-3.25f).toFloat() + 3.25f)).isLessThan(0.001f)
    }
}
