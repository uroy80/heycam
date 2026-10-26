package heycam.core.export

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.common.StagingArea
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.imaging.ImageFormat
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RenderSpec
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.ImageInfo
import heycam.core.model.PageEdits
import heycam.core.model.PageText
import heycam.core.model.Rotation
import heycam.core.pdf.PdfBoxPdfService
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** All members throw; fakes delegate to it and override what the test touches, so interface growth never breaks them. */
inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name} is not used here")
    } as T

object InPlace : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

/** Everything on one (test) dispatcher, so waits and timeouts run on its scheduler's virtual time. */
class OnDispatcher(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

/** Keeps warnings out of the way but shows them in the test report when something goes wrong. */
object SilentLog : HeycamLog {
    override fun d(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, error: Throwable?) = report(tag, message, error)
    override fun e(tag: String, message: String, error: Throwable?) = report(tag, message, error)

    private fun report(tag: String, message: String, error: Throwable?) {
        System.err.println("$tag: $message")
        error?.printStackTrace()
    }
}

val T0: Instant = Instant.parse("2026-10-20T09:02:00Z")

/** Documents and their text in memory; tests change them through [update] and [setText]. */
class FakeLibrary : DocumentRepository by unused() {
    private val documents = MutableStateFlow<Map<DocumentId, Document>>(emptyMap())
    private val texts = MutableStateFlow<Map<DocumentId, List<PageText>>>(emptyMap())

    fun put(document: Document) = documents.update { it + (document.id to document) }

    fun update(id: DocumentId, transform: (Document) -> Document) = documents.update { it + (id to transform(it.getValue(id))) }

    fun setText(id: DocumentId, pageTexts: List<PageText>) = texts.update { it + (id to pageTexts) }

    override fun observeDocument(id: DocumentId): Flow<Document?> = documents.map { it[id] }

    override fun observeText(id: DocumentId): Flow<List<PageText>> = texts.map { it[id].orEmpty() }
}

class FakeFiles(private val context: Context) : FileStore by unused() {
    override fun exportDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }
}

/**
 * Renders with the JDK's image tools: quarter turns, scaling to the preset's long edge, JPEG at its quality or PNG.
 * Filters are not applied; export does not care how pixels look.
 */
class ImageIoRenderer : PageRenderer {
    val calls = mutableListOf<Pair<PageEdits, RenderSpec>>()
    var failWith: Throwable? = null

    init {
        System.setProperty("java.awt.headless", "true")
    }

    override suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo {
        failWith?.let { throw it }
        calls += edits to spec
        var image = ImageIO.read(File(source.path))
        if (edits.rotation != Rotation.R0) image = rotate(image, edits.rotation.degrees)
        val long = max(image.width, image.height)
        val limit = spec.maxLongEdgePx
        if (limit != null && long > limit) {
            val scale = limit.toDouble() / long
            val scaled = BufferedImage(
                (image.width * scale).roundToInt(),
                (image.height * scale).roundToInt(),
                BufferedImage.TYPE_INT_RGB,
            )
            scaled.createGraphics().apply { drawImage(image, 0, 0, scaled.width, scaled.height, null); dispose() }
            image = scaled
        }
        if (spec.format == ImageFormat.PNG) {
            ImageIO.write(image, "png", out)
        } else {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = spec.quality / 100f
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            writer.dispose()
        }
        return ImageInfo(out.path, image.width, image.height, out.length(), spec.format.mime)
    }

    private fun rotate(image: BufferedImage, degrees: Int): BufferedImage {
        val quarter = degrees == 90 || degrees == 270
        val w = if (quarter) image.height else image.width
        val h = if (quarter) image.width else image.height
        val transform = AffineTransform().apply {
            translate(w / 2.0, h / 2.0)
            rotate(Math.toRadians(degrees.toDouble()))
            translate(-image.width / 2.0, -image.height / 2.0)
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR).filter(image, out)
        return out
    }

    override suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap =
        throw UnsupportedOperationException("previews are not part of export")

    override suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int): File =
        throw UnsupportedOperationException("thumbnails are not part of export")
}

object TestImages {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun jpeg(file: File, width: Int, height: Int): ImageInfo {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, width, height)
            color = Color.DARK_GRAY
            fillRect(width / 10, height / 10, width / 2, height / 25)
            dispose()
        }
        check(ImageIO.write(image, "jpeg", file))
        return ImageInfo(file.path, width, height, file.length(), "image/jpeg")
    }
}

fun pdfService(context: Context, staging: File) =
    PdfBoxPdfService(context, InPlace, Clock { T0 }, StagingArea { ext -> File.createTempFile("s", ".$ext", staging) })

/**
 * A folder provider backed by a directory, enough for DocumentFile.fromTreeUri + createFile + openOutputStream:
 * the shape of the system "Save to" folder picker's result.
 */
class DirectoryDocumentsProvider : DocumentsProvider() {
    lateinit var root: File

    override fun onCreate(): Boolean = true

    private fun file(documentId: String) = if (documentId == ROOT) root else File(root, documentId)

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = file(documentId)
        return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)).apply {
            addRow(arrayOf(documentId, f.name, if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE))
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).apply {
            root.list()?.forEach { addRow(arrayOf(it)) }
        }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        var name = displayName
        var n = 2
        while (File(root, name).exists()) {
            name = "${displayName.substringBeforeLast('.')} ($n).${displayName.substringAfterLast('.')}"
            n++
        }
        File(root, name).createNewFile()
        return name
    }

    override fun deleteDocument(documentId: String) {
        file(documentId).delete()
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = true

    companion object {
        const val AUTHORITY = "heycam.test.documents"
        const val ROOT = "root"
    }
}
