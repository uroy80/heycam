package heycam.core.testing.pdf

import heycam.core.model.Password
import heycam.core.model.StagedImage
import heycam.core.pdf.PdfInfo
import heycam.core.pdf.PdfService
import heycam.core.pdf.PdfSpec
import heycam.core.pdf.PdfStats
import java.io.File
import java.io.OutputStream
import java.util.Collections

/**
 * [PdfService] that records what it is asked and writes a tiny stand-in file instead of a real PDF:
 * "%PDF-1.7", a comment carrying the page count, "%%EOF". [inspect], [merge] and [split] read that count back, so
 * flows over several calls stay consistent. [rasterize] hands out [rasterizedPages].
 */
class RecordingPdfService(var rasterizedPages: List<StagedImage> = emptyList()) : PdfService {
    sealed interface Call {
        data class Write(val spec: PdfSpec) : Call
        data class Inspect(val input: File, val withPassword: Boolean) : Call
        data class Merge(val inputs: List<File>) : Call
        data class Split(val input: File, val ranges: List<IntRange>) : Call
        data class Protect(val input: File) : Call
        data class Unlock(val input: File) : Call
        data class Rasterize(val input: File, val dpi: Int) : Call
    }

    private val _calls = Collections.synchronizedList(mutableListOf<Call>())
    val calls: List<Call> get() = synchronized(_calls) { _calls.toList() }
    val written: List<PdfSpec> get() = calls.filterIsInstance<Call.Write>().map { it.spec }

    override suspend fun write(spec: PdfSpec, out: OutputStream, onPage: (done: Int, total: Int) -> Unit): PdfStats {
        require(spec.pages.isNotEmpty()) { "a PDF needs at least one page" }
        _calls += Call.Write(spec)
        spec.pages.indices.forEach { onPage(it + 1, spec.pages.size) }
        val bytes = standIn(spec.pages.size)
        out.write(bytes)
        val lines = if (spec.textLayer) spec.pages.sumOf { it.lines.size } else 0
        return PdfStats(pages = spec.pages.size, bytes = bytes.size.toLong(), textLines = lines)
    }

    override suspend fun inspect(input: File, password: Password?): PdfInfo {
        _calls += Call.Inspect(input, password != null)
        val text = input.readText(Charsets.US_ASCII)
        val locked = LOCKED in text
        return PdfInfo(pageCount = if (locked && password == null) 0 else pages(input), encrypted = locked, needsPassword = locked && password == null)
    }

    override suspend fun merge(inputs: List<File>, out: OutputStream) {
        _calls += Call.Merge(inputs)
        out.write(standIn(inputs.sumOf(::pages)))
    }

    override suspend fun split(input: File, ranges: List<IntRange>, out: (rangeIndex: Int) -> OutputStream) {
        _calls += Call.Split(input, ranges)
        ranges.forEachIndexed { i, range -> out(i).use { it.write(standIn(range.count())) } }
    }

    override suspend fun protect(input: File, password: Password, out: OutputStream) {
        _calls += Call.Protect(input)
        out.write(standIn(pages(input), locked = true))
    }

    override suspend fun unlock(input: File, password: Password, out: OutputStream) {
        _calls += Call.Unlock(input)
        out.write(standIn(pages(input)))
    }

    override suspend fun rasterize(input: File, dpi: Int, onPage: suspend (index: Int, image: StagedImage) -> Unit) {
        _calls += Call.Rasterize(input, dpi)
        rasterizedPages.forEachIndexed { i, image -> onPage(i, image) }
    }

    companion object {
        private const val LOCKED = "% locked"

        fun standIn(pages: Int, locked: Boolean = false): ByteArray =
            ("%PDF-1.7\n% heycam test PDF: $pages pages\n" + (if (locked) "$LOCKED\n" else "") + "%%EOF\n").toByteArray(Charsets.US_ASCII)

        /** The page count of a stand-in file. */
        fun pages(file: File): Int =
            Regex("""heycam test PDF: (\d+) pages""").find(file.readText(Charsets.US_ASCII))?.groupValues?.get(1)?.toInt() ?: 0
    }
}
