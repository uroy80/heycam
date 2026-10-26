package heycam.core.pdf

import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.Password
import heycam.core.model.StagedImage
import heycam.core.model.TextLine
import java.io.File
import java.io.OutputStream

interface PdfService {
    /** Images embedded as-is (DCTDecode passthrough); optional glyphless invisible text layer; header 1.7; Producer "heycam". */
    suspend fun write(spec: PdfSpec, out: OutputStream, onPage: (done: Int, total: Int) -> Unit = { _, _ -> }): PdfStats

    // ---- v1.1 ----
    suspend fun inspect(input: File, password: Password? = null): PdfInfo
    suspend fun merge(inputs: List<File>, out: OutputStream)
    suspend fun split(input: File, ranges: List<IntRange>, out: (rangeIndex: Int) -> OutputStream)
    suspend fun protect(input: File, password: Password, out: OutputStream)   // AES-256 (V5/R6)
    suspend fun unlock(input: File, password: Password, out: OutputStream)
    /** PDF import: framework PdfRenderer at [dpi] (default 200), one JPEG per page into staging. */
    suspend fun rasterize(input: File, dpi: Int, onPage: suspend (index: Int, image: StagedImage) -> Unit)
}

data class PdfSpec(
    val title: String,
    val pages: List<PdfPage>,
    val pageSize: PageSize,
    val margin: Margin,
    val textLayer: Boolean,
    val password: Password? = null,   // v1.1
    val pageNumbers: Boolean = false, // v1.1
    val watermark: String? = null,    // v1.1
)

/** [lines] are in [image]'s pixel space (ExportService scales them from the OCR render). */
data class PdfPage(val image: File, val mime: String, val widthPx: Int, val heightPx: Int, val lines: List<TextLine>)

data class PdfStats(val pages: Int, val bytes: Long, val textLines: Int)

data class PdfInfo(val pageCount: Int, val encrypted: Boolean, val needsPassword: Boolean)
