package heycam.core.imaging

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Size
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Decode and encode helpers shared by the importer and the renderer. Every bitmap they make is software ARGB_8888 sRGB. */
internal object Codec {
    /** Upper bound for any bitmap heycam decodes (64 MB as ARGB_8888); keeps one bitmap plus its pixel array in budget. */
    const val MAX_DECODE_PIXELS = 16_000_000L

    /**
     * Decodes [file] upright (ImageDecoder applies EXIF orientation), scaled down by the factor [scaleFor] returns for
     * the oriented size (capped at 1 and at [MAX_DECODE_PIXELS]). [checkHeader] may throw to abort before any pixels
     * are decoded. The result is mutable.
     */
    fun decode(file: File, checkHeader: (Size) -> Unit = {}, scaleFor: (Size) -> Float = { 1f }): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val size = info.size
            checkHeader(size)
            val pixels = size.width.toLong() * size.height
            val cap = sqrt(MAX_DECODE_PIXELS.toDouble() / pixels).toFloat()
            val scale = min(1f, min(scaleFor(size), cap))
            if (scale < 1f) {
                decoder.setTargetSize(
                    (size.width * scale).roundToInt().coerceAtLeast(1),
                    (size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        return toArgb8888(decoded)
    }

    /** Scale that fits [size]'s long edge into [maxLongEdgePx] (null = no limit); never above 1. */
    fun fitScale(size: Size, maxLongEdgePx: Int?): Float =
        if (maxLongEdgePx == null) 1f else min(1f, maxLongEdgePx.toFloat() / max(size.width, size.height))

    /** Writes [bitmap] to [out] through a temporary sibling, so readers never see a half-written file. Never writes EXIF. */
    fun write(bitmap: Bitmap, format: ImageFormat, quality: Int, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, ".${out.name}.tmp")
        try {
            val encoded = tmp.outputStream().buffered().use { stream ->
                when (format) {
                    ImageFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)
                    ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
            if (!encoded) throw IOException("image encoder failed")
            if (!tmp.renameTo(out)) throw IOException("could not move the image into place")
        } finally {
            tmp.delete()
        }
    }

    private fun toArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: throw IOException("could not convert the image")
        bitmap.recycle()
        return copy
    }
}
