package heycam.core.imaging

import android.content.ContentResolver
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.common.StagingArea
import heycam.core.model.PageOrigin
import heycam.core.model.StagedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Photo Picker and share intake (PLAN 3.11, P4, P7): copies the content right away (the read grant is temporary),
 * decodes it with ImageDecoder (JPEG, PNG, WebP, HEIF, GIF first frame; EXIF orientation applied), caps the long edge
 * at [MAX_LONG_EDGE_PX], flattens transparency onto white and re-encodes as JPEG q95, which drops every metadata
 * block (GPS included). The type an app declares is never trusted; only a successful decode counts.
 */
@Singleton
internal class DecodingImageImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val staging: StagingArea,
    private val dispatchers: DispatcherProvider,
    private val log: HeycamLog,
) : ImageImporter {

    override suspend fun import(uri: Uri, origin: PageOrigin): ImportedImage {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return ImportedImage.Rejected(RejectReason.UNREADABLE)
        val copy = staging.newStagedFile(COPY_EXTENSION)
        try {
            when (val copied = withContext(dispatchers.io) { copy(uri, copy) }) {
                is Copy.Failed -> return ImportedImage.Rejected(copied.reason)
                Copy.Done -> Unit
            }
            return withContext(dispatchers.default) { normalise(copy, origin) }
        } finally {
            copy.delete()
        }
    }

    private sealed interface Copy {
        data object Done : Copy
        data class Failed(val reason: RejectReason) : Copy
    }

    private fun copy(uri: Uri, target: File): Copy = try {
        val input = context.contentResolver.openInputStream(uri) ?: return Copy.Failed(RejectReason.UNREADABLE)
        input.use { source -> target.outputStream().buffered().use { copyCapped(source, it) } }
    } catch (error: IOException) {
        log.w(TAG, "could not read a picked image", error)
        Copy.Failed(RejectReason.UNREADABLE)
    } catch (error: SecurityException) {
        log.w(TAG, "no permission to read a picked image", error)
        Copy.Failed(RejectReason.UNREADABLE)
    }

    private fun copyCapped(source: InputStream, sink: OutputStream): Copy {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return Copy.Failed(RejectReason.TOO_LARGE)
            sink.write(buffer, 0, read)
        }
        return if (total == 0L) Copy.Failed(RejectReason.NOT_AN_IMAGE) else Copy.Done
    }

    private fun normalise(copy: File, origin: PageOrigin): ImportedImage {
        val bitmap = try {
            Codec.decode(
                copy,
                checkHeader = { size -> if (size.width.toLong() * size.height > MAX_PIXELS) throw TooManyPixels() },
                scaleFor = { size -> Codec.fitScale(size, MAX_LONG_EDGE_PX) },
            )
        } catch (error: TooManyPixels) {
            return ImportedImage.Rejected(RejectReason.TOO_LARGE)
        } catch (error: OutOfMemoryError) {
            log.w(TAG, "image too large to decode", error)
            return ImportedImage.Rejected(RejectReason.TOO_LARGE)
        } catch (error: ImageDecoder.DecodeException) {
            val reason = if (error.error == ImageDecoder.DecodeException.SOURCE_INCOMPLETE) RejectReason.UNREADABLE else RejectReason.NOT_AN_IMAGE
            return ImportedImage.Rejected(reason)
        } catch (error: IOException) {
            return ImportedImage.Rejected(RejectReason.NOT_AN_IMAGE)
        }
        val out = staging.newStagedFile(ImageFormat.JPEG.extension)
        try {
            if (bitmap.hasAlpha()) Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
            Codec.write(bitmap, ImageFormat.JPEG, JPEG_QUALITY, out)
            return ImportedImage.Ok(StagedImage(out, bitmap.width, bitmap.height, ImageFormat.JPEG.mime, origin))
        } catch (error: IOException) {
            out.delete()
            log.w(TAG, "could not save an imported image", error)
            return ImportedImage.Rejected(RejectReason.UNREADABLE)
        } finally {
            bitmap.recycle()
        }
    }

    /** Thrown from the header callback so nothing is decoded for an oversized image. */
    private class TooManyPixels : IOException()

    internal companion object {
        private const val TAG = "import"
        private const val COPY_EXTENSION = "part"
        private const val BUFFER_BYTES = 64 * 1024
        const val MAX_BYTES = 50L * 1024 * 1024
        const val MAX_PIXELS = 50_000_000L
        const val MAX_LONG_EDGE_PX = 4000
        const val JPEG_QUALITY = 95
    }
}
