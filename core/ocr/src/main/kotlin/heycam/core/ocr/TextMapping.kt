package heycam.core.ocr

import com.google.mlkit.vision.text.Text
import heycam.core.model.BoxPx
import heycam.core.model.TextLine
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns ML Kit's result into heycam's line-level text (PLAN 3.9). Pure apart from ML Kit's own value classes. */
internal object TextMapping {
    /** OCR decodes at most this long an edge (PLAN 3.7); boxes are scaled back to the full image. */
    const val MAX_DECODE_LONG_EDGE = 3000

    data class Page(val text: String, val lines: List<TextLine>)

    /**
     * Lines keep their box in the full image's pixels ([scaleX], [scaleY] map decoded pixels back), ML Kit's clockwise
     * angle and confidence. Text is NFC; lines are joined by "\n" and blocks by "\n\n". Lines without text or a box
     * are dropped.
     */
    fun map(result: Text, scaleX: Float, scaleY: Float): Page {
        val blocks = ArrayList<List<TextLine>>()
        for (block in result.textBlocks) {
            val lines = block.lines.mapNotNull { line ->
                val text = nfc(line.text).trim()
                val box = line.boundingBox
                if (text.isEmpty() || box == null || box.isEmpty) return@mapNotNull null
                TextLine(
                    text = text,
                    box = BoxPx(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY),
                    angleDeg = line.angle,
                    confidence = line.confidence.takeIf { it in 0f..1f },
                )
            }
            if (lines.isNotEmpty()) blocks += lines
        }
        return Page(
            text = blocks.joinToString("\n\n") { lines -> lines.joinToString("\n") { it.text } },
            lines = blocks.flatten(),
        )
    }

    fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    /** The decode size for a [width] × [height] image: its own size, or scaled so the long edge is [MAX_DECODE_LONG_EDGE]. */
    fun decodeSize(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MAX_DECODE_LONG_EDGE) return width to height
        val scale = MAX_DECODE_LONG_EDGE.toFloat() / long
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
