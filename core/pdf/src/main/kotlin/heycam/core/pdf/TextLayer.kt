package heycam.core.pdf

import heycam.core.model.BoxPx
import heycam.core.model.TextLine
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A recognised line as it sits in the image: the start of its baseline, its direction and the size of the line's
 * own (unrotated) rectangle, in image pixels with y pointing down.
 */
internal data class LineRect(
    val baselineX: Float,
    val baselineY: Float,
    val width: Float,
    val height: Float,
    val cos: Float,
    val sin: Float,
)

/** One line ready for the content stream, in PDF points (y up). [cos]/[sin] give the text direction. */
internal data class PlacedLine(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val size: Float,
    val cos: Float,
    val sin: Float,
)

/**
 * The invisible text layer (PLAN 3.10): render mode 3, one Tj per OCR line, font size = line height, horizontal
 * scaling (Tz) so the line spans its box, and the line angle applied through the text matrix. The glyphless font's
 * glyph sits on the baseline at the bottom of its 0..1000 box, and [CidAllocator] gives each character its advance,
 * so a line drawn at its box's bottom-left with size = box height covers exactly the box. A gently tilted line is
 * [level]led first, so that poppler keeps it in one piece.
 */
internal object TextLayer {
    const val FONT_RESOURCE = "F1"

    /** Lines within half a degree of horizontal are placed as axis-aligned boxes. */
    private const val STRAIGHT_DEG = 0.5f

    /** Near 45° the bounding box no longer tells width from height; such lines fall back to their box. */
    private const val MIN_DETERMINANT = 0.3

    /**
     * How far, as a share of the font size, a line's baseline may climb or fall from one end to the other. Poppler
     * (pdftotext, Evince, Okular) starts a new line, even in the middle of a word, once a glyph's baseline is about
     * half the font size away from where the line began; PDFium and PDFKit follow the angle whatever it is.
     */
    const val MAX_RISE = 0.4f

    /** How far, as a share of the font size, [level] may move each end of a line; a steeper line keeps its angle. */
    const val MAX_END_SHIFT = 0.6f

    /**
     * Recovers the line's own rectangle from ML Kit's axis-aligned [box] and its clockwise [angleDeg]. A w×h
     * rectangle turned by θ has a bounding box of W = w|cos θ| + h|sin θ| by H = w|sin θ| + h|cos θ|, which is solved
     * for w and h; the bounding box's top-left then pins down where the rotated rectangle starts.
     */
    fun lineRect(box: BoxPx, angleDeg: Float): LineRect {
        val straight = LineRect(box.left, box.bottom, box.width, box.height, 1f, 0f)
        if (abs(angleDeg) < STRAIGHT_DEG) return straight
        val t = Math.toRadians(angleDeg.toDouble())
        val c = cos(t)
        val s = sin(t)
        val a = abs(c)
        val b = abs(s)
        val det = a * a - b * b
        if (abs(det) < MIN_DETERMINANT) return straight
        val w = (box.width * a - box.height * b) / det
        val h = (box.height * a - box.width * b) / det
        if (w <= 0 || h <= 0) return straight
        // Corners relative to the line's top-left p0: p0, p0 + w·u, p0 + h·v, p0 + w·u + h·v with u = (c, s), v = (-s, c).
        val minX = minOf(0.0, w * c, -h * s, w * c - h * s)
        val minY = minOf(0.0, w * s, h * c, w * s + h * c)
        val p0x = box.left - minX
        val p0y = box.top - minY
        return LineRect(
            baselineX = (p0x - h * s).toFloat(),
            baselineY = (p0y + h * c).toFloat(),
            width = w.toFloat(),
            height = h.toFloat(),
            cos = c.toFloat(),
            sin = s.toFloat(),
        )
    }

    /** Maps a line from image pixels ([imageWidthPx] × [imageHeightPx]) onto the page; null when nothing is left. */
    fun place(line: TextLine, imageWidthPx: Int, imageHeightPx: Int, placement: Placement): PlacedLine? {
        val text = clean(line.text)
        if (text.isEmpty() || line.box.width <= 0f || line.box.height <= 0f) return null
        val sx = placement.imageWidth / imageWidthPx
        val sy = placement.imageHeight / imageHeightPx
        val rect = lineRect(line.box, line.angleDeg)
        // Image y grows downwards and PDF y upwards, so a clockwise angle in the image is counter-clockwise on paper.
        return level(
            PlacedLine(
                text = text,
                x = placement.imageX + rect.baselineX * sx,
                y = placement.imageY + (imageHeightPx - rect.baselineY) * sy,
                width = rect.width * sx,
                size = rect.height * sy,
                cos = rect.cos,
                sin = -rect.sin,
            ),
        )
    }

    /**
     * Turns a tilted line towards horizontal about its centre until its baseline climbs or falls by no more than
     * [MAX_RISE] × size over its length, so poppler reads it as one line of whole words (a 6° line came out as
     * "Tokyo Stat" / "ion" before). The search hit and the selection then sit off the printed words by at most
     * [MAX_END_SHIFT] × size at the line's ends and not at all at its centre. A line that would move further keeps
     * its own angle: there, a selection that follows the print matters more than poppler.
     */
    fun level(line: PlacedLine): PlacedLine {
        val rise = abs(line.width * line.sin)
        val allowed = MAX_RISE * line.size
        if (rise <= allowed || line.width <= 0f) return line
        if ((rise - allowed) / 2f > MAX_END_SHIFT * line.size) return line
        val sin = sign(line.sin) * allowed / line.width
        val cos = sqrt(1f - sin * sin)
        val centreX = line.x + line.cos * line.width / 2f
        val centreY = line.y + line.sin * line.width / 2f
        return line.copy(x = centreX - cos * line.width / 2f, y = centreY - sin * line.width / 2f, cos = cos, sin = sin)
    }

    /**
     * Tz operand: 100 × boxWidth / (advance × size), with the line's natural advance in em. For a line of plain
     * half-em glyphs this is PLAN 3.10's 100 × boxWidth / (glyphCount × size × 0.5).
     */
    fun horizontalScale(widthPt: Float, sizePt: Float, ems: Float): Float = 100f * widthPt / (ems * sizePt)

    /** NFC, control characters turned into spaces, trimmed. */
    fun clean(text: String): String = buildString {
        for (ch in Normalizer.normalize(text, Normalizer.Form.NFC)) append(if (ch.isISOControl()) ' ' else ch)
    }.trim()

    /** The BT…ET block for one page; empty when there are no lines. Allocates CIDs in [cids] as it goes. */
    fun content(lines: List<PlacedLine>, cids: CidAllocator): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder("BT\n3 Tr\n")
        for (line in lines) {
            val codes = cids.encode(line.text)
            if (codes.isEmpty()) continue
            sb.append('/').append(FONT_RESOURCE).append(' ').append(num(line.size)).append(" Tf\n")
            sb.append(num(horizontalScale(line.width, line.size, cids.ems(codes)))).append(" Tz\n")
            sb.append(num(line.cos)).append(' ').append(num(line.sin)).append(' ')
                .append(num(-line.sin)).append(' ').append(num(line.cos)).append(' ')
                .append(num(line.x)).append(' ').append(num(line.y)).append(" Tm\n<")
            codes.forEach { sb.append(CidAllocator.hex4(it)) }
            sb.append("> Tj\n")
        }
        return sb.append("ET\n").toString()
    }

    /** PDF number: at most 3 decimals, no exponent, no trailing zeros. */
    fun num(value: Float): String {
        val s = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
