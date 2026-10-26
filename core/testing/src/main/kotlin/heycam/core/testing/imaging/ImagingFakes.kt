package heycam.core.testing.imaging

import android.graphics.Bitmap
import android.net.Uri
import heycam.core.common.StagingArea
import heycam.core.imaging.ImageImporter
import heycam.core.imaging.ImportedImage
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RejectReason
import heycam.core.imaging.RenderSpec
import heycam.core.model.ImageInfo
import heycam.core.model.PageEdits
import heycam.core.model.PageOrigin
import heycam.core.model.Rotation
import heycam.core.model.StagedImage
import java.io.File
import java.util.Collections
import kotlin.math.max
import kotlin.math.roundToInt

/** Sample pages shipped in this module's resources (heycam/samples/). */
enum class Sample(val fileName: String, val widthPx: Int, val heightPx: Int) {
    /** The spike's A4 invoice at 150 dpi: English ("HEYCAM SPIKE TEST INVOICE", "Invoice No. HC-2026-104") and Hindi ("धन्यवाद"). */
    EN_HI("sample_en_hi.jpg", 1240, 1754),

    /** A blank A4 page at 150 dpi. */
    BLANK("blank.jpg", 1240, 1754),
    ;

    /** Copies the sample to [target] and returns it. */
    fun copyTo(target: File): File {
        target.parentFile?.mkdirs()
        val stream = requireNotNull(Sample::class.java.classLoader?.getResourceAsStream("heycam/samples/$fileName")) { "missing sample $fileName" }
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** Stages a copy as an importer or the scanner would. */
    fun stage(staging: StagingArea, origin: PageOrigin = PageOrigin.SCANNER): StagedImage =
        StagedImage(copyTo(staging.newStagedFile("jpg")), widthPx, heightPx, "image/jpeg", origin)
}

/**
 * [PageRenderer] that copies instead of rendering: [render] and [thumbnail] copy the input file to `out`, reporting
 * the size the real renderer would produce (rotation and the long-edge limit applied; crop ignored). [preview] needs
 * Robolectric (it makes a blank Bitmap). Calls are recorded; set [failWith] to make them throw.
 */
class FakePageRenderer : PageRenderer {
    data class RenderCall(val source: ImageInfo, val edits: PageEdits, val spec: RenderSpec, val out: File)

    private val _renders = Collections.synchronizedList(mutableListOf<RenderCall>())
    val renders: List<RenderCall> get() = synchronized(_renders) { _renders.toList() }
    @Volatile var failWith: Throwable? = null

    override suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo {
        failWith?.let { throw it }
        _renders += RenderCall(source, edits, spec, out)
        out.parentFile?.mkdirs()
        File(source.path).copyTo(out, overwrite = true)
        val (w, h) = size(source, edits.rotation, spec.maxLongEdgePx)
        return ImageInfo(out.absolutePath, w, h, out.length(), spec.format.mime)
    }

    override suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap {
        failWith?.let { throw it }
        val (w, h) = size(source, edits.rotation, maxLongEdgePx)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    override suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int): File {
        failWith?.let { throw it }
        out.parentFile?.mkdirs()
        return File(image.path).copyTo(out, overwrite = true)
    }

    private fun size(source: ImageInfo, rotation: Rotation, maxLongEdgePx: Int?): Pair<Int, Int> {
        val quarter = rotation == Rotation.R90 || rotation == Rotation.R270
        val w = if (quarter) source.heightPx else source.widthPx
        val h = if (quarter) source.widthPx else source.heightPx
        val scale = maxLongEdgePx?.let { minOf(1f, it.toFloat() / max(w, h)) } ?: 1f
        return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }
}

/**
 * [ImageImporter] that stages [sample] for every uri, except those listed in [rejections]. [imported] records the
 * uris in call order.
 */
class FakeImageImporter(
    private val staging: StagingArea,
    var sample: Sample = Sample.EN_HI,
) : ImageImporter {
    val rejections = Collections.synchronizedMap(mutableMapOf<Uri, RejectReason>())
    private val _imported = Collections.synchronizedList(mutableListOf<Uri>())
    val imported: List<Uri> get() = synchronized(_imported) { _imported.toList() }

    override suspend fun import(uri: Uri, origin: PageOrigin): ImportedImage {
        _imported += uri
        rejections[uri]?.let { return ImportedImage.Rejected(it) }
        return ImportedImage.Ok(sample.stage(staging, origin))
    }
}
