package heycam.core.model

import java.time.Instant

enum class OcrScript { LATIN, DEVANAGARI, CHINESE, JAPANESE, KOREAN }

/** PENDING = queued; DONE = text stored; EMPTY = read, no text found; FAILED = engine error; SKIPPED = auto-text off. */
enum class TextStatus { PENDING, DONE, EMPTY, FAILED, SKIPPED }

/** Pixel box in the image the text was read from (Page.render at [PageText.editsVersion]). */
data class BoxPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun scaled(sx: Float, sy: Float) = BoxPx(left * sx, top * sy, right * sx, bottom * sy)
}

/** Line-level geometry only: ML Kit word boxes are off by up to ~15 px (mlkit-spike), line boxes are accurate. */
data class TextLine(
    val text: String,                 // NFC
    val box: BoxPx,
    val angleDeg: Float = 0f,
    val confidence: Float? = null,
)

data class PageText(
    val pageId: PageId,
    val script: OcrScript,
    val text: String,                 // NFC; lines joined by \n, blocks by \n\n
    val lines: List<TextLine>,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val editsVersion: Int,
    val engine: String,               // e.g. "mlkit-text-v2/19.0.1"
    val recognizedAt: Instant,
)
