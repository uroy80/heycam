package heycam.core.pdf

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.StagingArea
import heycam.core.model.PageOrigin
import heycam.core.model.Password
import heycam.core.model.StagedImage
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** PDFBox-Android implementation of [PdfService]. Only this module touches com.tom_roush.* (PLAN 3.2). */
@Singleton
class PdfBoxPdfService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val staging: StagingArea,
) : PdfService {

    @Volatile private var resourcesLoaded = false

    /** PDFBox needs its bundled resources (glyph list, standard-14 metrics) before the first document. */
    private fun ensureResources() {
        if (resourcesLoaded) return
        synchronized(this) {
            if (!resourcesLoaded) {
                PDFBoxResourceLoader.init(context.applicationContext)
                resourcesLoaded = true
            }
        }
    }

    private val producer: String by lazy {
        val version = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.versionName
        }.getOrNull()
        if (version.isNullOrBlank()) PRODUCER else "$PRODUCER $version"
    }

    override suspend fun write(spec: PdfSpec, out: OutputStream, onPage: (done: Int, total: Int) -> Unit): PdfStats =
        withContext(dispatchers.io) {
            require(spec.pages.isNotEmpty()) { "a PDF needs at least one page" }
            ensureResources()
            val counted = CountingOutputStream(out)
            var textLines = 0
            PDDocument(MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES)).use { doc ->
                val font = if (spec.textLayer) TextLayerFont(doc) else null
                val watermark = spec.watermark?.trim()?.takeIf { it.isNotEmpty() }?.let { Watermark(doc, it) }
                spec.pages.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    textLines += addPage(doc, page, spec, font, watermark, index)
                    onPage(index + 1, spec.pages.size)
                }
                font?.finish()
                describe(doc, spec.title)
                spec.password?.let { protect(doc, it) }
                headerAtLeast17(doc)
                doc.save(counted)
            }
            PdfStats(pages = spec.pages.size, bytes = counted.count, textLines = textLines)
        }

    /** Adds one page and returns how many text lines went into its text layer. */
    private fun addPage(
        doc: PDDocument,
        page: PdfPage,
        spec: PdfSpec,
        font: TextLayerFont?,
        watermark: Watermark?,
        index: Int,
    ): Int {
        require(page.mime == JPEG_MIME) { "pages are embedded as JPEG; got ${page.mime}" }
        require(page.widthPx > 0 && page.heightPx > 0) { "page size must be positive" }
        val jpeg = Jpeg.read(page.image)
        val placement = PageLayout.place(spec.pageSize, spec.margin, jpeg.width, jpeg.height)
        val pdPage = PDPage(PDRectangle(placement.pageWidth, placement.pageHeight))
        doc.addPage(pdPage)
        val resources = PDResources()
        pdPage.resources = resources

        val image = page.image.inputStream().buffered().use { input ->
            val colorSpace = if (jpeg.components == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
            PDImageXObject(doc, input, COSName.DCT_DECODE, jpeg.width, jpeg.height, 8, colorSpace)
        }
        if (jpeg.components == 4) {
            // PDFBox-Android has no CMYK colour space class; the dictionary entry is all a viewer needs.
            image.cosObject.setItem(COSName.COLORSPACE, COSName.DEVICECMYK)
            if (jpeg.adobeCmyk) image.decode = COSArray().apply { repeat(4) { add(COSInteger.ONE); add(COSInteger.ZERO) } }
        }
        val imageName = resources.add(image)

        val content = StringBuilder()
        with(placement) {
            content.append("q ").append(TextLayer.num(imageWidth)).append(" 0 0 ").append(TextLayer.num(imageHeight))
                .append(' ').append(TextLayer.num(imageX)).append(' ').append(TextLayer.num(imageY))
                .append(" cm /").append(imageName.name).append(" Do Q\n")
        }
        watermark?.let { content.append(it.draw(resources, placement)) }

        var lines = 0
        if (font != null && page.lines.isNotEmpty()) {
            resources.cosObject.setItem(
                COSName.FONT,
                COSDictionary().apply { setItem(COSName.getPDFName(TextLayer.FONT_RESOURCE), font.dictionary) },
            )
            // Lines arrive in the page's pixel space (widthPx × heightPx); the JPEG decides where they land.
            val placed = page.lines.mapNotNull { TextLayer.place(it, page.widthPx, page.heightPx, placement) }
            content.append(TextLayer.content(placed, font.cids))
            lines = placed.size
        }
        if (spec.pageNumbers) content.append(pageNumber(resources, placement, index + 1, spec.pages.size))

        pdPage.setContents(PDStream(doc, content.toString().byteInputStream(Charsets.US_ASCII), COSName.FLATE_DECODE))
        return lines
    }

    /** "n / N" centred near the bottom edge on a small white plate so it reads on dark scans too. */
    private fun pageNumber(resources: PDResources, placement: Placement, number: Int, total: Int): String {
        val font = PDType1Font.HELVETICA
        val fontName = resources.add(font).name
        val label = "$number / $total"
        val width = font.getStringWidth(label) / 1000f * PAGE_NUMBER_SIZE
        val x = (placement.pageWidth - width) / 2f
        val y = PAGE_NUMBER_BASELINE
        val pad = 3f
        return buildString {
            append("q 1 g ").append(TextLayer.num(x - pad)).append(' ').append(TextLayer.num(y - pad)).append(' ')
                .append(TextLayer.num(width + 2 * pad)).append(' ').append(TextLayer.num(PAGE_NUMBER_SIZE + pad))
                .append(" re f Q\n")
            append("BT 0.2 g /").append(fontName).append(' ').append(TextLayer.num(PAGE_NUMBER_SIZE)).append(" Tf ")
                .append(TextLayer.num(x)).append(' ').append(TextLayer.num(y)).append(" Td (").append(label)
                .append(") Tj ET\n")
        }
    }

    private fun describe(doc: PDDocument, title: String) {
        doc.documentInformation = PDDocumentInformation().apply {
            this.title = title
            producer = this@PdfBoxPdfService.producer
            creationDate = Calendar.getInstance().apply { timeInMillis = clock.now().toEpochMilli() }
        }
        doc.documentCatalog.viewerPreferences = PDViewerPreferences(COSDictionary()).apply { setDisplayDocTitle(true) }
    }

    /** AES-256 (V5/R6). PDFBox needs a String, so the password briefly exists as one; the char copy is wiped. */
    private fun protect(doc: PDDocument, password: Password) {
        val chars = password.chars()
        try {
            val text = String(chars)
            val policy = StandardProtectionPolicy(text, text, AccessPermission())
            policy.encryptionKeyLength = 256
            doc.protect(policy)
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * PLAN 3.10 wants a 1.7 header. PDDocument.setVersion only writes the catalog's /Version once the header is 1.4
     * (and AES-256 needs 1.7), so the header itself is raised; newer headers are kept.
     */
    private fun headerAtLeast17(doc: PDDocument) {
        if (doc.document.version < PDF_VERSION) doc.document.version = PDF_VERSION
    }

    override suspend fun inspect(input: File, password: Password?): PdfInfo = withContext(dispatchers.io) {
        ensureResources()
        try {
            load(input, password).use { PdfInfo(pageCount = it.numberOfPages, encrypted = it.isEncrypted, needsPassword = false) }
        } catch (_: InvalidPasswordException) {
            PdfInfo(pageCount = 0, encrypted = true, needsPassword = true)
        }
    }

    override suspend fun merge(inputs: List<File>, out: OutputStream) = withContext(dispatchers.io) {
        require(inputs.isNotEmpty()) { "nothing to merge" }
        ensureResources()
        PDFMergerUtility().apply {
            inputs.forEach { addSource(it) }
            destinationStream = CountingOutputStream(out)
        }.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
    }

    override suspend fun split(input: File, ranges: List<IntRange>, out: (rangeIndex: Int) -> OutputStream) =
        withContext(dispatchers.io) {
            ensureResources()
            load(input, null).use { source ->
                ranges.forEachIndexed { rangeIndex, range ->
                    require(!range.isEmpty() && range.first >= 0 && range.last < source.numberOfPages) {
                        "page range $range is outside 0 until ${source.numberOfPages}"
                    }
                    PDDocument().use { part ->
                        for (pageIndex in range) part.importPage(source.getPage(pageIndex))
                        headerAtLeast17(part)
                        out(rangeIndex).use { part.save(it) }
                    }
                }
            }
        }

    override suspend fun protect(input: File, password: Password, out: OutputStream) = withContext(dispatchers.io) {
        ensureResources()
        load(input, null).use { doc ->
            protect(doc, password)
            headerAtLeast17(doc)
            doc.save(CountingOutputStream(out))
        }
    }

    override suspend fun unlock(input: File, password: Password, out: OutputStream) = withContext(dispatchers.io) {
        ensureResources()
        load(input, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(CountingOutputStream(out))
        }
    }

    private fun load(input: File, password: Password?): PDDocument {
        val chars = password?.chars() ?: CharArray(0)
        try {
            return PDDocument.load(input, String(chars), MemoryUsageSetting.setupTempFileOnly())
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * Renders each page with the framework PdfRenderer (white background, long edge capped like imports) into a
     * staged JPEG. Password-protected files must be [unlock]ed first.
     */
    override suspend fun rasterize(input: File, dpi: Int, onPage: suspend (index: Int, image: StagedImage) -> Unit) {
        require(dpi > 0) { "dpi must be positive" }
        withContext(dispatchers.io) {
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (index in 0 until renderer.pageCount) {
                        currentCoroutineContext().ensureActive()
                        val staged = renderer.openPage(index).use { page -> renderPage(page, dpi) }
                        onPage(index, staged)
                    }
                }
            }
        }
    }

    private fun renderPage(page: PdfRenderer.Page, dpi: Int): StagedImage {
        var w = page.width * dpi / 72f
        var h = page.height * dpi / 72f
        val cap = MAX_RASTER_LONG_EDGE_PX / max(w, h)
        if (cap < 1f) {
            w *= cap
            h *= cap
        }
        val bitmap = Bitmap.createBitmap(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val file = staging.newStagedFile("jpg")
            file.outputStream().buffered().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, RASTER_QUALITY, it)) throw IOException("JPEG encode failed") }
            return StagedImage(file, bitmap.width, bitmap.height, JPEG_MIME, PageOrigin.PDF_IMPORT)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The document-wide glyphless Type0 font. Its ToUnicode, CIDToGIDMap and widths are written once every page is
     * laid out, because CIDs are allocated as the pages' lines are encoded.
     */
    private class TextLayerFont(doc: PDDocument) {
        val cids = CidAllocator()
        private val toUnicode = doc.document.createCOSStream()
        private val cidToGid = doc.document.createCOSStream()
        private val cidFont: COSDictionary
        val dictionary: COSDictionary

        init {
            val fontFile = doc.document.createCOSStream().apply {
                setInt(COSName.LENGTH1, GlyphlessFont.bytes.size)
                createOutputStream(COSName.FLATE_DECODE).use { it.write(GlyphlessFont.bytes) }
            }
            val descriptor = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT_DESC)
                setName(COSName.FONT_NAME, GlyphlessFont.NAME)
                setInt(COSName.FLAGS, FLAGS_SYMBOLIC)
                setItem(COSName.FONT_BBOX, intArray(0, 0, CidAllocator.WIDE_ADVANCE, GlyphlessFont.ASCENT))
                setInt(COSName.ITALIC_ANGLE, 0)
                setInt(COSName.ASCENT, GlyphlessFont.ASCENT)
                // Not 0: Apple's CoreGraphics (Preview, PDFKit) cannot place a font without a descent and then
                // ignores the whole text layer (G2, docs/pdf-compat.txt). Tesseract's glyphless font uses -1 too.
                setInt(COSName.DESCENT, -1)
                setInt(COSName.CAP_HEIGHT, GlyphlessFont.ASCENT)
                setInt(COSName.STEM_V, 80)
                setItem(COSName.FONT_FILE2, fontFile)
            }
            cidFont = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2)
                setName(COSName.BASE_FONT, GlyphlessFont.NAME)
                setItem(COSName.CIDSYSTEMINFO, COSDictionary().apply {
                    setString(COSName.REGISTRY, "Adobe")
                    setString(COSName.ORDERING, "Identity")
                    setInt(COSName.SUPPLEMENT, 0)
                })
                setItem(COSName.FONT_DESC, descriptor)
                setInt(COSName.DW, GlyphlessFont.ADVANCE)
                setItem(COSName.CID_TO_GID_MAP, cidToGid)
            }
            dictionary = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.TYPE0)
                setName(COSName.BASE_FONT, GlyphlessFont.NAME)
                setItem(COSName.ENCODING, COSName.IDENTITY_H)
                setItem(COSName.DESCENDANT_FONTS, COSArray().apply { add(cidFont) })
                setItem(COSName.TO_UNICODE, toUnicode)
            }
        }

        fun finish() {
            toUnicode.createOutputStream(COSName.FLATE_DECODE).use {
                it.write(cids.toUnicodeCMap().toByteArray(Charsets.US_ASCII))
            }
            cidToGid.createOutputStream(COSName.FLATE_DECODE).use { it.write(cids.cidToGidMap()) }
            val runs = cids.widthRuns()
            if (runs.isNotEmpty()) {
                cidFont.setItem(COSName.W, COSArray().apply {
                    for ((first, widths) in runs) {
                        add(COSInteger.get(first.toLong()))
                        add(intArray(*widths.toIntArray()))
                    }
                })
            }
        }

        private fun intArray(vararg values: Int) = COSArray().apply { values.forEach { add(COSInteger.get(it.toLong())) } }
    }

    /**
     * A diagonal watermark drawn as a translucent image, so any script prints without embedding a real font.
     * The image is built once per document and reused on every page.
     */
    private class Watermark(doc: PDDocument, text: String) {
        private val image: PDImageXObject
        private val aspect: Float

        init {
            val label = text.take(WATERMARK_MAX_CHARS)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = WATERMARK_TEXT_PX
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val natural = paint.measureText(label)
            if (natural > WATERMARK_MAX_WIDTH_PX) paint.textSize *= WATERMARK_MAX_WIDTH_PX / natural
            val metrics = paint.fontMetrics
            val pad = paint.textSize / 4f
            val width = ceil(paint.measureText(label) + 2 * pad).toInt().coerceAtLeast(1)
            val height = ceil(metrics.descent - metrics.ascent + 2 * pad).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawText(label, pad, pad - metrics.ascent, paint)
                image = LosslessFactory.createFromImage(doc, bitmap)
            } finally {
                bitmap.recycle()
            }
            aspect = height.toFloat() / width
        }

        fun draw(resources: PDResources, placement: Placement): String {
            val state = PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = WATERMARK_ALPHA
                strokingAlphaConstant = WATERMARK_ALPHA
            }
            val gs = resources.add(state).name
            val name = resources.add(image).name
            val angle = atan2(placement.pageHeight, placement.pageWidth)
            val length = WATERMARK_DIAGONAL_SHARE * hypot(placement.pageWidth, placement.pageHeight)
            val thickness = length * aspect
            val c = cos(angle)
            val s = sin(angle)
            val cx = placement.pageWidth / 2f
            val cy = placement.pageHeight / 2f
            // Unit image square → length × thickness, rotated by angle about the page centre.
            val e = cx - length / 2f * c + thickness / 2f * s
            val f = cy - length / 2f * s - thickness / 2f * c
            return "q /$gs gs ${TextLayer.num(length * c)} ${TextLayer.num(length * s)} ${TextLayer.num(-thickness * s)} " +
                "${TextLayer.num(thickness * c)} ${TextLayer.num(e)} ${TextLayer.num(f)} cm /$name Do Q\n"
        }
    }

    /** Counts bytes and never closes the caller's stream (PDFBox closes whatever it saves to). */
    private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()

        override fun close() = target.flush()
    }

    private companion object {
        const val PRODUCER = "heycam"
        const val JPEG_MIME = "image/jpeg"
        const val PDF_VERSION = 1.7f
        const val MAX_MAIN_MEMORY_BYTES = 16L * 1024 * 1024
        const val FLAGS_SYMBOLIC = 4
        const val PAGE_NUMBER_SIZE = 9f
        const val PAGE_NUMBER_BASELINE = 14f
        const val WATERMARK_TEXT_PX = 160f
        const val WATERMARK_MAX_CHARS = 60
        const val WATERMARK_MAX_WIDTH_PX = 3000f
        const val WATERMARK_ALPHA = 0.15f
        const val WATERMARK_DIAGONAL_SHARE = 0.6f
        const val MAX_RASTER_LONG_EDGE_PX = 4000f
        const val RASTER_QUALITY = 92
    }
}
