package heycam.core.imaging

import android.graphics.Bitmap
import android.net.Uri
import heycam.core.model.Adjustments
import heycam.core.model.ImageInfo
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.StagedImage
import java.io.File

/** Copies a picked/shared image into staging: ImageDecoder (EXIF orientation applied), <= 4000 px long edge, JPEG q95, no metadata. */
interface ImageImporter {
    suspend fun import(uri: Uri, origin: PageOrigin): ImportedImage
}

sealed interface ImportedImage {
    data class Ok(val image: StagedImage) : ImportedImage
    data class Rejected(val reason: RejectReason) : ImportedImage
}

enum class RejectReason { NOT_AN_IMAGE, TOO_LARGE, UNREADABLE }

interface PageRenderer {
    /** Applies crop -> rotation -> filter -> adjustments; writes [out]; output never carries EXIF/GPS. */
    suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo
    /** Downsampled bitmap for previews (filter strip, review screen). Caller recycles. */
    suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap
    suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int = 480): File
}

data class RenderSpec(val maxLongEdgePx: Int?, val format: ImageFormat = ImageFormat.JPEG, val quality: Int = 90)

enum class ImageFormat(val mime: String, val extension: String) { JPEG("image/jpeg", "jpg"), PNG("image/png", "png") }

/** Pure-Kotlin pixel filters on ARGB_8888 IntArrays so they unit-test on the JVM without Robolectric. In place. */
interface PixelFilters {
    fun apply(argb: IntArray, width: Int, height: Int, filter: PageFilter, adjust: Adjustments = Adjustments.NONE)
}
