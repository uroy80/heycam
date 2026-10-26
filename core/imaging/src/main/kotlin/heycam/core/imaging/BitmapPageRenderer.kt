package heycam.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Size
import heycam.core.common.DispatcherProvider
import heycam.core.model.Adjustments
import heycam.core.model.ImageInfo
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PointN
import heycam.core.model.Quad
import heycam.core.model.Rotation
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/**
 * Applies [PageEdits] to a page source: crop, then rotation, then filter, then adjustments (PLAN 3.11).
 * The source is decoded only as large as the output needs, and at most one full-size bitmap is alive at a time
 * (plus its pixel array while a filter runs).
 */
@Singleton
internal class BitmapPageRenderer @Inject constructor(
    private val filters: PixelFilters,
    private val dispatchers: DispatcherProvider,
) : PageRenderer {

    override suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo =
        withContext(dispatchers.default) {
            val bitmap = draw(File(source.path), edits, spec.maxLongEdgePx)
            try {
                Codec.write(bitmap, spec.format, spec.quality, out)
                ImageInfo(out.absolutePath, bitmap.width, bitmap.height, out.length(), spec.format.mime)
            } finally {
                bitmap.recycle()
            }
        }

    override suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap {
        require(maxLongEdgePx > 0) { "preview size must be positive" }
        return withContext(dispatchers.default) { draw(File(source.path), edits, maxLongEdgePx) }
    }

    override suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int): File {
        require(maxLongEdgePx > 0) { "thumbnail size must be positive" }
        return withContext(dispatchers.default) {
            val bitmap = Codec.decode(File(image.path)) { Codec.fitScale(it, maxLongEdgePx) }
            try {
                Codec.write(bitmap, ImageFormat.JPEG, THUMB_QUALITY, out)
            } finally {
                bitmap.recycle()
            }
            out
        }
    }

    private fun draw(file: File, edits: PageEdits, maxLongEdgePx: Int?): Bitmap {
        if (!file.isFile) throw IOException("page image is missing")
        val crop = edits.crop
        // Decode just large enough that the cropped result meets the size limit.
        var bitmap = Codec.decode(file) { size -> Codec.fitScale(cropSize(size, crop), maxLongEdgePx) }
        if (crop != null) bitmap = replace(bitmap, perspectiveCrop(bitmap, crop))
        if (edits.filter == PageFilter.ORIGINAL && edits.adjust == Adjustments.NONE) {
            return if (edits.rotation == Rotation.R0) bitmap else replace(bitmap, rotated(bitmap, edits.rotation))
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        if (edits.rotation != Rotation.R0) bitmap.recycle()
        filters.apply(pixels, width, height, edits.filter, edits.adjust)
        return if (edits.rotation == Rotation.R0) {
            bitmap.apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        } else {
            rotatedFromPixels(pixels, width, height, edits.rotation)
        }
    }

    /** Recycles [old] once [new] has been made from it. */
    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (new !== old) old.recycle()
        return new
    }

    private fun rotated(bitmap: Bitmap, rotation: Rotation): Bitmap {
        val matrix = Matrix().apply { postRotate(rotation.degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    /** Writes [pixels] (width x height) into a new bitmap turned clockwise by [rotation], one source row at a time. */
    private fun rotatedFromPixels(pixels: IntArray, width: Int, height: Int, rotation: Rotation): Bitmap {
        val quarter = rotation == Rotation.R90 || rotation == Rotation.R270
        val out = Bitmap.createBitmap(if (quarter) height else width, if (quarter) width else height, Bitmap.Config.ARGB_8888)
        val reversed = IntArray(width)
        for (y in 0 until height) {
            val offset = y * width
            when (rotation) {
                // Source row y becomes column (height - 1 - y), read top to bottom.
                Rotation.R90 -> out.setPixels(pixels, offset, 1, height - 1 - y, 0, 1, width)
                // Source row y becomes column y, read bottom to top.
                Rotation.R270 -> {
                    reverseRow(pixels, offset, width, reversed)
                    out.setPixels(reversed, 0, 1, y, 0, 1, width)
                }
                // Source row y becomes row (height - 1 - y), reversed.
                Rotation.R180 -> {
                    reverseRow(pixels, offset, width, reversed)
                    out.setPixels(reversed, 0, width, 0, height - 1 - y, width, 1)
                }
                Rotation.R0 -> out.setPixels(pixels, offset, width, 0, y, width, 1)
            }
        }
        return out
    }

    private fun reverseRow(pixels: IntArray, offset: Int, width: Int, out: IntArray) {
        for (x in 0 until width) out[x] = pixels[offset + width - 1 - x]
    }

    /** Maps the quad (normalised source corners) onto an upright rectangle the size of its longer opposite edges. */
    private fun perspectiveCrop(bitmap: Bitmap, crop: Quad): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val corners = listOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft).map { clamp(it) }
        val size = cropSize(Size(bitmap.width, bitmap.height), crop)
        val src = corners.flatMap { listOf(it.x * w, it.y * h) }.toFloatArray()
        val outW = size.width.toFloat()
        val outH = size.height.toFloat()
        val dst = floatArrayOf(0f, 0f, outW, 0f, outW, outH, 0f, outH)
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) throw IOException("crop corners do not form a quadrilateral")
        val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    private fun clamp(p: PointN) = PointN(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

    /** Output size of [crop] on an image of [size]: the longer of each pair of opposite edges. */
    private fun cropSize(size: Size, crop: Quad?): Size {
        if (crop == null) return size
        fun edge(a: PointN, b: PointN) = hypot(
            (clamp(a).x - clamp(b).x) * size.width.toDouble(),
            (clamp(a).y - clamp(b).y) * size.height.toDouble(),
        )
        val width = max(edge(crop.topLeft, crop.topRight), edge(crop.bottomLeft, crop.bottomRight))
        val height = max(edge(crop.topLeft, crop.bottomLeft), edge(crop.topRight, crop.bottomRight))
        return Size(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
    }

    private companion object {
        const val THUMB_QUALITY = 80
    }
}
