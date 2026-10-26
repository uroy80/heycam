package heycam.core.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import heycam.core.common.StagingArea
import heycam.core.model.PageOrigin
import heycam.core.model.StagedImage
import java.io.File
import java.io.IOException

/** The most pages one scan keeps: the scanner is told to stop there, and the stager keeps no more if it does not. */
internal const val MAX_SCAN_PAGES = 100

/**
 * Moves the scanner's result JPEGs into heycam's staging area and deletes ML Kit's copies, which it never cleans up
 * itself (mlkit-spike). Pages are copied byte for byte minus their metadata (P4); only a page whose EXIF says it is
 * turned is decoded and saved upright, because the orientation tag goes with the metadata.
 */
internal class ScanPageStager(private val context: Context, private val staging: StagingArea) {

    /** Stages every page or none: on failure the pages staged so far are deleted. ML Kit's files go either way. */
    fun stage(uris: List<Uri>): List<StagedImage> {
        val staged = ArrayList<StagedImage>()
        try {
            for (uri in uris.take(MAX_SCAN_PAGES)) staged += stageOne(uri)
            return staged
        } catch (e: Exception) {
            staged.forEach { it.file.delete() }
            throw e
        } finally {
            uris.forEach(::deleteOriginal)
        }
    }

    private fun stageOne(uri: Uri): StagedImage {
        checkSource(uri)
        val target = staging.newStagedFile("jpg")
        try {
            val info = open(uri).use { input -> target.outputStream().buffered().use { JpegMetadata.strip(input, it) } }
            if (info.orientation == 1) return StagedImage(target, info.width, info.height, JPEG, PageOrigin.SCANNER)
            return saveUpright(uri, target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** ImageDecoder applies the EXIF orientation; the result is re-encoded without metadata. */
    private fun saveUpright(uri: Uri, target: File): StagedImage {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            target.outputStream().buffered().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, UPRIGHT_QUALITY, out)) throw IOException("JPEG encode failed")
            }
            return StagedImage(target, bitmap.width, bitmap.height, JPEG, PageOrigin.SCANNER)
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(uri: Uri) = context.contentResolver.openInputStream(uri) ?: throw IOException("Scanner page unreadable")

    /** The scanner hands back files in this app's cache; anything else is refused rather than read. */
    private fun checkSource(uri: Uri) {
        if (uri.scheme == "file" && localFile(uri) == null) throw SecurityException("Scanner page outside the app cache")
    }

    private fun deleteOriginal(uri: Uri) {
        localFile(uri)?.delete()
    }

    private fun localFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null).canonicalFile
        val cache = context.cacheDir.canonicalFile
        return file.takeIf { it.path.startsWith(cache.path + File.separator) }
    }

    private companion object {
        const val JPEG = "image/jpeg"
        const val UPRIGHT_QUALITY = 95
    }
}
