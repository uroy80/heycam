package heycam.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.StagingArea
import heycam.core.model.BoxPx
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Runs everything in place so tests stay deterministic. */
object InPlaceDispatchers : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

val FIXED_NOW: Instant = Instant.parse("2026-10-20T09:02:00Z")

fun pdfService(context: Context, stagingDir: File) = PdfBoxPdfService(
    context = context,
    dispatchers = InPlaceDispatchers,
    clock = Clock { FIXED_NOW },
    staging = StagingArea { ext -> File.createTempFile("staged", ".$ext", stagingDir) },
)

fun PdfService.writeBytes(spec: PdfSpec): ByteArray =
    ByteArrayOutputStream().also { out -> runBlocking { write(spec, out) } }.toByteArray()

fun extractText(pdf: ByteArray, page: Int? = null, password: String = ""): String =
    PDDocument.load(pdf, password).use { doc ->
        PDFTextStripper().apply {
            if (page != null) {
                startPage = page
                endPage = page
            }
        }.getText(doc)
    }

/** Every glyph PDFBox places on one page, in display coordinates (y down from the top of the page). */
fun glyphs(pdf: ByteArray, page: Int = 1): List<TextPosition> = PDDocument.load(pdf).use { doc ->
    val found = mutableListOf<TextPosition>()
    object : PDFTextStripper() {
        override fun processTextPosition(text: TextPosition) {
            found += text
            super.processTextPosition(text)
        }
    }.apply {
        startPage = page
        endPage = page
    }.getText(doc)
    found
}

/** The axis-aligned box ML Kit reports for a w×h line whose top-left is at ([left], [top]), turned clockwise by [angleDeg]. */
fun rotatedBox(left: Float, top: Float, w: Float, h: Float, angleDeg: Float): BoxPx {
    val t = Math.toRadians(angleDeg.toDouble())
    val c = cos(t)
    val s = sin(t)
    val xs = listOf(0.0, w * c, -h * s, w * c - h * s).map { left + it }
    val ys = listOf(0.0, w * s, h * c, w * s + h * c).map { top + it }
    return BoxPx(xs.min().toFloat(), ys.min().toFloat(), xs.max().toFloat(), ys.max().toFloat())
}

/** A line box as wide as a typical font would set [text] at [sizePx] (10 % wider than the natural advances). */
fun naturalWidth(text: String, sizePx: Float): Float =
    text.codePoints().toArray().sumOf { CidAllocator.advanceOfCodePoint(it) } / 1000f * sizePx * 1.1f
