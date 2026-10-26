package heycam.core.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.imaging.ImageFormat
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RenderSpec
import heycam.core.model.Document
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.ImageInfo
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.SizePreset
import heycam.core.model.TextLine
import heycam.core.model.TextStatus
import heycam.core.pdf.PdfPage
import heycam.core.pdf.PdfService
import heycam.core.pdf.PdfSpec
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PDF (searchable), JPG/PNG per page and TXT (PLAN 3.12), written to a file the user picked, a folder the user
 * picked, or heycam's share cache behind a FileProvider. Pages are rendered one at a time at the size preset;
 * Original JPEG pages are passed through without re-encoding.
 */
@Singleton
class DefaultExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documents: DocumentRepository,
    private val files: FileStore,
    private val renderer: PageRenderer,
    private val pdf: PdfService,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : ExportService {

    override fun suggestedFileName(title: String, options: ExportOptions, index: Int?): String =
        FileNames.name(baseName(options.fileName ?: title), options.format, index)

    private fun baseName(name: String) = FileNames.sanitize(name, context.getString(R.string.export_fallback_name))

    override suspend fun estimate(request: ExportRequest): SizeEstimate = withContext(dispatchers.io) {
        val options = request.options
        val sources = load(request, withText = options.format == ExportFormat.TEXT)
            ?: return@withContext SizeEstimate(0, measured = false)
        val pages = sources.flatMap { it.pages }
        if (options.format == ExportFormat.TEXT) {
            return@withContext SizeEstimate(sources.sumOf { textOf(it).toByteArray(Charsets.UTF_8).size.toLong() }, measured = true)
        }
        val overhead = if (options.format == ExportFormat.PDF) PDF_BASE_BYTES * sources.size + PDF_PAGE_BYTES * pages.size else 0L
        // One page is encoded at the preset and the others are sized by their pixels, so pages of mixed size add up.
        // The largest is measured: it weighs most in the total, and a tiny page's bytes are mostly JPEG headers.
        val sample = pages.filter { passthrough(options, it) == null }.maxByOrNull { exportPixels(it, options.size) }
            ?: return@withContext SizeEstimate(pages.sumOf { passthrough(options, it)?.bytes ?: 0L } + overhead, measured = true)
        val work = newWorkDir()
        try {
            val measured = exportImage(sample, options, imageFormat(options.format), work)
            val sampleBytes = measured.file.length()
            val bytesPerPixel = sampleBytes.toDouble() / (measured.width.toLong() * measured.height)
            val bytes = pages.sumOf { page ->
                val kept = passthrough(options, page)
                when {
                    page === sample -> sampleBytes
                    kept != null -> kept.bytes
                    else -> (bytesPerPixel * exportPixels(page, options.size)).roundToLong()
                }
            }
            SizeEstimate(bytes + overhead, measured = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storedSizes(pages, overhead, e)
        } catch (e: OutOfMemoryError) {
            storedSizes(pages, overhead, e)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun storedSizes(pages: List<Page>, overhead: Long, error: Throwable): SizeEstimate {
        log.w(TAG, "Size estimate fell back to stored sizes", error)
        return SizeEstimate(pages.sumOf { (current(it) ?: it.source).bytes } + overhead, measured = false)
    }

    /** The page's pixels once exported at [size]: its current image (the source until its edits are rendered), fitted. */
    private fun exportPixels(page: Page, size: SizePreset): Double {
        val image = current(page) ?: page.source
        val limit = size.maxLongEdgePx
        val scale = if (limit == null) 1.0 else min(1.0, limit.toDouble() / max(image.widthPx, image.heightPx))
        return image.widthPx * scale * (image.heightPx * scale)
    }

    override suspend fun export(request: ExportRequest, target: ExportTarget, onProgress: (ExportProgress) -> Unit): ExportResult =
        withContext(dispatchers.io) {
            // The system picker made the picked file, empty, before this was called. Whatever stops the export
            // (a failure, or the user leaving while it waits for text) removes it rather than leave it in their folder.
            // A TextPending answer keeps it: the export that follows the question writes into it.
            val picked = (target as? ExportTarget.Document)?.uri
            val result = try {
                write(request, target, onProgress)
            } catch (e: CancellationException) {
                picked?.let(::deleteQuietly)
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Export failed", e)
                ExportResult.Failed(failureOf(e))
            } catch (e: OutOfMemoryError) {
                log.w(TAG, "Export ran out of memory", e)
                ExportResult.Failed(ExportFailure.WRITE_FAILED)
            }
            if (result is ExportResult.Failed && picked != null) deleteQuietly(picked)
            result
        }

    override suspend fun discard(target: ExportTarget) {
        val picked = (target as? ExportTarget.Document)?.uri ?: return
        withContext(dispatchers.io) { deleteQuietly(picked) }
    }

    private suspend fun write(request: ExportRequest, target: ExportTarget, onProgress: (ExportProgress) -> Unit): ExportResult {
        val options = request.options
        val withText = options.format == ExportFormat.TEXT || (options.format == ExportFormat.PDF && options.searchable)
        var sources = load(request, withText) ?: return ExportResult.Failed(ExportFailure.SOURCE_MISSING)
        if (withText) {
            val pending = sources.sumOf { source -> source.pages.count { it.textStatus == TextStatus.PENDING } }
            if (pending > 0) {
                when (request.whenTextPending) {
                    TextPendingPolicy.ASK -> return ExportResult.TextPending(pending)
                    TextPendingPolicy.WAIT -> {
                        awaitText(sources)
                        sources = load(request, withText) ?: return ExportResult.Failed(ExportFailure.SOURCE_MISSING)
                    }
                    TextPendingPolicy.WITHOUT_TEXT -> Unit
                }
            }
        }
        val missing = sources.flatMap { it.pages }.any { !File((current(it) ?: it.source).path).isFile }
        if (missing) return ExportResult.Failed(ExportFailure.SOURCE_MISSING)
        val work = newWorkDir()
        try {
            val progress = Progress(sources.sumOf { it.pages.size }, onProgress)
            val outputs = outputs(sources, options, work, progress)
            return when (target) {
                ExportTarget.Share -> share(outputs, options.format)
                is ExportTarget.Document -> saveToDocument(outputs, target.uri)
                is ExportTarget.Folder -> saveToFolder(outputs, target.treeUri)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** One PDF with every requested page and no text layer, for printing; the caller deletes it. */
    internal suspend fun printablePdf(request: ExportRequest): PrintablePdf = withContext(dispatchers.io) {
        val sources = load(request, withText = false) ?: throw ExportException(ExportFailure.SOURCE_MISSING)
        val options = request.options.copy(format = ExportFormat.PDF, searchable = false)
        val name = sources.singleOrNull()?.let { baseName(it.document.title) } ?: context.getString(R.string.export_print_job)
        val dir = File(files.exportDir(), UUID.randomUUID().toString()).apply { mkdirs() }
        val work = newWorkDir()
        try {
            val pages = sources.flatMap { source -> source.pages.map { pdfPage(source, it, options, work) } }
            val file = File(dir, FileNames.name(name, ExportFormat.PDF, null))
            file.outputStream().buffered().use { out ->
                pdf.write(PdfSpec(name, pages, options.pageSize, options.margin, textLayer = false), out)
            }
            PrintablePdf(file, name, pages.size)
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: ExportException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw ExportException(failureOf(e), e)
        } catch (e: OutOfMemoryError) {
            dir.deleteRecursively()
            throw ExportException(ExportFailure.WRITE_FAILED, e)
        } finally {
            work.deleteRecursively()
        }
    }

    // ---- sources --------------------------------------------------------------------------------------------------

    private class Source(val document: Document, val pages: List<Page>, val texts: Map<PageId, PageText>)

    /** The requested, non-trashed documents with the selected pages; null when one is gone or in the trash. */
    private suspend fun load(request: ExportRequest, withText: Boolean): List<Source>? {
        val selected = request.options.pages?.toSet()
        val sources = request.documentIds.distinct().map { id ->
            val document = documents.observeDocument(id).first() ?: return null
            if (document.trashedAt != null) return null
            val pages = if (selected == null) document.pages else document.pages.filter { it.id in selected }
            val texts = if (withText) documents.observeText(id).first().associateBy { it.pageId } else emptyMap()
            Source(document, pages, texts)
        }.filter { it.pages.isNotEmpty() }
        return sources.ifEmpty { null }
    }

    /**
     * Waits until no selected page is waiting for its text, or until none has been read for [WAIT_IDLE_MS]: a text
     * model that cannot download, or a page nothing is reading, must not hold the export forever. Pages still
     * waiting then go without text.
     */
    private suspend fun awaitText(sources: List<Source>) {
        val pending = combine(
            sources.map { source ->
                val ids = source.pages.map { it.id }.toSet()
                documents.observeDocument(source.document.id).map { document ->
                    document?.pages?.count { it.id in ids && it.textStatus == TextStatus.PENDING } ?: 0
                }
            },
        ) { counts -> counts.sum() }
        var fewest = Int.MAX_VALUE
        while (fewest > 0) {
            fewest = withTimeoutOrNull(WAIT_IDLE_MS) { pending.first { it < fewest } } ?: return
        }
    }

    // ---- outputs --------------------------------------------------------------------------------------------------

    private class Output(val name: String, val mime: String, val write: suspend (OutputStream) -> Unit)

    private class Progress(private val total: Int, private val listener: (ExportProgress) -> Unit) {
        private var done = 0

        fun advance(pages: Int = 1) {
            done = (done + pages).coerceAtMost(total)
            listener(ExportProgress(done, total))
        }
    }

    private fun outputs(sources: List<Source>, options: ExportOptions, work: File, progress: Progress): List<Output> {
        val base = { source: Source -> baseName(options.fileName?.takeIf { sources.size == 1 } ?: source.document.title) }
        val mime = FileNames.mime(options.format)
        return when (options.format) {
            ExportFormat.PDF -> sources.map { source ->
                Output(FileNames.name(base(source), ExportFormat.PDF, null), mime) { out ->
                    val pages = source.pages.map { page -> pdfPage(source, page, options, work).also { progress.advance() } }
                    val spec = PdfSpec(
                        title = source.document.title,
                        pages = pages,
                        pageSize = options.pageSize,
                        margin = options.margin,
                        textLayer = options.searchable,
                        password = options.password,
                        pageNumbers = options.pageNumbers,
                        watermark = options.watermark,
                    )
                    pdf.write(spec, out)
                    pages.forEach { if (it.image.parentFile == work) it.image.delete() }
                }
            }
            ExportFormat.JPEG, ExportFormat.PNG -> sources.flatMap { source ->
                source.pages.mapIndexed { index, page ->
                    Output(FileNames.name(base(source), options.format, index), mime) { out ->
                        val image = exportImage(page, options, imageFormat(options.format), work)
                        image.file.inputStream().use { it.copyTo(out) }
                        if (image.file.parentFile == work) image.file.delete()
                        progress.advance()
                    }
                }
            }
            ExportFormat.TEXT -> sources.map { source ->
                Output(FileNames.name(base(source), ExportFormat.TEXT, null), mime) { out ->
                    out.write(textOf(source).toByteArray(Charsets.UTF_8))
                    progress.advance(source.pages.size)
                }
            }
        }
    }

    private suspend fun pdfPage(source: Source, page: Page, options: ExportOptions, work: File): PdfPage {
        val image = exportImage(page, options, ImageFormat.JPEG, work)
        val lines = if (options.searchable) linesFor(source.texts[page.id], page, image) else emptyList()
        return PdfPage(image.file, ImageFormat.JPEG.mime, image.width, image.height, lines)
    }

    private class ExportImage(val file: File, val width: Int, val height: Int)

    /** The page with its edits, at the preset size: Original JPEG passes the current image through untouched. */
    private suspend fun exportImage(page: Page, options: ExportOptions, format: ImageFormat, work: File): ExportImage {
        val kept = passthrough(options, page)
        if (kept != null && format == ImageFormat.JPEG) return ExportImage(File(kept.path), kept.widthPx, kept.heightPx)
        val current = current(page)
        val out = File(work, "page-${page.id.value}.${format.extension}")
        val spec = RenderSpec(options.size.maxLongEdgePx, format, options.size.jpegQuality)
        val info = if (current != null) {
            renderer.render(current, PageEdits(), spec, out)
        } else {
            renderer.render(page.source, page.edits, spec, out)
        }
        return ExportImage(out, info.widthPx, info.heightPx)
    }

    /** The image with the page's edits applied, if it exists and is up to date. */
    private fun current(page: Page): ImageInfo? = page.render ?: page.source.takeIf { page.edits.isIdentity }

    /** The current image when [options] keep its bytes as they are (Original, a JPEG, not exported as PNG). */
    private fun passthrough(options: ExportOptions, page: Page): ImageInfo? =
        current(page)?.takeIf {
            options.size == SizePreset.ORIGINAL && options.format != ExportFormat.PNG && it.mime == ImageFormat.JPEG.mime
        }

    /**
     * OCR lines scaled into the exported image's pixels. Only text that was read for the page's current geometry
     * counts (status DONE); a render whose shape no longer matches the text is left without a layer.
     */
    private fun linesFor(text: PageText?, page: Page, image: ExportImage): List<TextLine> {
        if (text == null || page.textStatus != TextStatus.DONE || text.lines.isEmpty()) return emptyList()
        if (text.imageWidthPx <= 0 || text.imageHeightPx <= 0) return emptyList()
        val sx = image.width / text.imageWidthPx.toFloat()
        val sy = image.height / text.imageHeightPx.toFloat()
        if (abs(sx - sy) / max(sx, sy) > MAX_SCALE_MISMATCH) return emptyList()
        return text.lines.map { it.copy(box = it.box.scaled(sx, sy)) }
    }

    /** Pages joined; with several pages each starts with a "Page N" line. */
    private fun textOf(source: Source): String {
        val pages = source.pages.map { source.texts[it.id]?.text?.trim().orEmpty() }
        val body = if (pages.size == 1) {
            pages.single()
        } else {
            pages.mapIndexed { i, text ->
                val heading = context.getString(R.string.export_text_page, i + 1)
                if (text.isEmpty()) heading else "$heading\n$text"
            }.joinToString("\n\n")
        }
        return if (body.isEmpty()) body else body + "\n"
    }

    // ---- targets --------------------------------------------------------------------------------------------------

    private suspend fun share(outputs: List<Output>, format: ExportFormat): ExportResult {
        val dir = File(files.exportDir(), UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val taken = HashSet<String>()
            val uris = outputs.map { output ->
                val file = File(dir, FileNames.unique(output.name, taken)).also { taken += it.name }
                file.outputStream().buffered().use { output.write(it) }
                FileProvider.getUriForFile(context, ExportFileProvider.authority(context.packageName), file)
            }
            return ExportResult.ReadyToShare(chooser(uris, FileNames.mime(format)))
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun chooser(uris: List<Uri>, mime: String): Intent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        // The chooser hands the grant on to the chosen app only through ClipData.
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private suspend fun saveToDocument(outputs: List<Output>, uri: Uri): ExportResult {
        val output = outputs.singleOrNull() ?: run {
            log.w(TAG, "A single file was picked for ${outputs.size} files")
            return ExportResult.Failed(ExportFailure.WRITE_FAILED)
        }
        // On failure export() removes the picked file, so no broken one is left behind.
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        stream.buffered().use { output.write(it) }
        return ExportResult.Saved(listOf(uri))
    }

    private suspend fun saveToFolder(outputs: List<Output>, treeUri: Uri): ExportResult {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return ExportResult.Failed(ExportFailure.WRITE_FAILED)
        val created = ArrayList<Uri>(outputs.size)
        try {
            for (output in outputs) {
                val file = folder.createFile(output.mime, output.name) ?: throw IOException("Folder refused a new file")
                created += file.uri
                val stream = context.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException("No output stream")
                stream.buffered().use { output.write(it) }
            }
        } catch (e: Throwable) {
            created.forEach(::deleteQuietly)
            throw e
        }
        return ExportResult.Saved(created)
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            log.w(TAG, "Could not remove a partial export", e)
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Scratch space for rendered pages, inside the exports cache so the startup sweep clears it after a crash. */
    private fun newWorkDir(): File = File(files.exportDir(), "work-${UUID.randomUUID()}").apply { mkdirs() }

    private fun imageFormat(format: ExportFormat) = if (format == ExportFormat.PNG) ImageFormat.PNG else ImageFormat.JPEG

    private fun failureOf(error: Throwable): ExportFailure = when {
        error is ExportException -> error.failure
        error is OperationCanceledException -> ExportFailure.CANCELLED
        generateSequence(error) { it.cause }.any { it.isNoSpace() } -> ExportFailure.NO_SPACE
        else -> ExportFailure.WRITE_FAILED
    }

    private fun Throwable.isNoSpace(): Boolean =
        (this is ErrnoException && errno == OsConstants.ENOSPC) || message?.contains("ENOSPC") == true

    private companion object {
        const val TAG = "Export"
        const val PDF_BASE_BYTES = 4_000L
        const val PDF_PAGE_BYTES = 1_500L
        const val MAX_SCALE_MISMATCH = 0.02f
        const val WAIT_IDLE_MS = 20_000L
    }
}

/** A PDF ready for the print framework. */
internal class PrintablePdf(val file: File, val jobName: String, val pageCount: Int)

/** An export that could not happen, with the reason the UI shows. */
internal class ExportException(val failure: ExportFailure, cause: Throwable? = null) : IOException(failure.name, cause)
