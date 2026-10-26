package heycam.core.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import heycam.core.model.PageOrigin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodingImageImporterTest {
    @get:Rule val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val staging by lazy { TempStaging(temp.root) }
    private val log = RecordingLog()
    private val importer by lazy { DecodingImageImporter(context, staging, TestDispatchers, log) }
    private var next = 0

    private fun register(stream: () -> InputStream): Uri {
        val uri = Uri.parse("content://picker.test/media/${next++}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { stream() }
        return uri
    }

    private fun register(file: File) = register { file.inputStream() }

    private fun register(bytes: ByteArray) = register { ByteArrayInputStream(bytes) }

    private suspend fun ok(uri: Uri, origin: PageOrigin = PageOrigin.PHOTO_PICKER): heycam.core.model.StagedImage {
        val result = importer.import(uri, origin)
        assertThat(result).isInstanceOf(ImportedImage.Ok::class.java)
        return (result as ImportedImage.Ok).image
    }

    private fun rejected(result: ImportedImage) = (result as ImportedImage.Rejected).reason

    @Test
    fun photoIsTurnedUprightAndStrippedOfMetadata() = runTest {
        val image = ok(register(fixture("photo_with_gps.jpg")), PageOrigin.SHARE)
        assertThat(image.origin).isEqualTo(PageOrigin.SHARE)
        assertThat(image.mime).isEqualTo("image/jpeg")
        assertThat(image.widthPx to image.heightPx).isEqualTo(480 to 640)
        assertThat(image.file.parentFile).isEqualTo(staging.dir)
        val bitmap = BitmapFactory.decodeFile(image.file.path)
        assertThat(bitmap.width to bitmap.height).isEqualTo(480 to 640)
        assertThat(isReddish(bitmap.getPixel(470, 10))).isTrue()

        val exif = ExifInterface(image.file.path)
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            .isAnyOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
    }

    @Test
    fun onlyTheStagedJpegIsLeftBehind() = runTest {
        val image = ok(register(fixture("photo_with_gps.jpg")))
        assertThat(staging.dir.list()!!.toList()).containsExactly(image.file.name)
    }

    @Test
    fun transparencyIsFlattenedOntoWhite() = runTest {
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            setPixels(IntArray(20 * 20) { Color.BLACK }, 0, 20, 40, 30, 20, 20)
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val image = ok(register(png))
        val decoded = BitmapFactory.decodeFile(image.file.path)
        assertThat(luma(decoded.getPixel(5, 5))).isGreaterThan(245)
        assertThat(luma(decoded.getPixel(50, 40))).isLessThan(20)
    }

    @Test
    fun largePhotosAreCappedAt4000OnTheLongEdge() = runTest {
        val big = markedBitmap(4400, 2200).writeTo(File(temp.root, "big.jpg"), quality = 70)
        val image = ok(register(big))
        assertThat(image.widthPx to image.heightPx).isEqualTo(4000 to 2000)
    }

    @Test
    fun moreThan50MegapixelsIsRejectedFromTheHeader() = runTest {
        assertThat(rejected(importer.import(register(pngHeaderOnly(8000, 7000)), PageOrigin.SHARE))).isEqualTo(RejectReason.TOO_LARGE)
    }

    @Test
    fun moreThan50MegabytesIsRejectedWhileCopying() = runTest {
        val uri = register { ZeroStream(DecodingImageImporter.MAX_BYTES + 1) }
        assertThat(rejected(importer.import(uri, PageOrigin.SHARE))).isEqualTo(RejectReason.TOO_LARGE)
        assertThat(staging.dir.list().orEmpty()).isEmpty()
    }

    @Test
    fun anythingThatDoesNotDecodeIsNotAnImage() = runTest {
        val text = "%PDF-1.7 pretending to be a photo".toByteArray()
        assertThat(rejected(importer.import(register(text), PageOrigin.SHARE))).isEqualTo(RejectReason.NOT_AN_IMAGE)
        assertThat(rejected(importer.import(register(ByteArray(0)), PageOrigin.SHARE))).isEqualTo(RejectReason.NOT_AN_IMAGE)
        assertThat(staging.dir.list().orEmpty()).isEmpty()
    }

    @Test
    fun readFailuresAreUnreadable() = runTest {
        val broken = register { object : InputStream() { override fun read(): Int = throw IOException("grant revoked") } }
        assertThat(rejected(importer.import(broken, PageOrigin.PHOTO_PICKER))).isEqualTo(RejectReason.UNREADABLE)
        assertThat(log.lines.single()).doesNotContain("grant revoked")
    }

    @Test
    fun onlyContentUrisAreAccepted() = runTest {
        val file = fixture("photo_with_gps.jpg")
        assertThat(rejected(importer.import(Uri.fromFile(file), PageOrigin.SHARE))).isEqualTo(RejectReason.UNREADABLE)
    }

    /** A PNG claiming [width] x [height] RGB whose pixel data stops after the first row: enough to read the header. */
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) }
        }.toByteArray()
        chunk("IHDR", ihdr)
        val firstRow = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(ByteArray(1 + width * 3)) } }
        chunk("IDAT", firstRow.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private class ZeroStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill(0, off, off + n)
            remaining -= n
            return n
        }
    }
}
