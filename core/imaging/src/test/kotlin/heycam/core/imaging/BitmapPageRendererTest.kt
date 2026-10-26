package heycam.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import heycam.core.model.ImageInfo
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PointN
import heycam.core.model.Quad
import heycam.core.model.Rotation
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapPageRendererTest {
    @get:Rule val temp = TemporaryFolder()

    private val renderer = BitmapPageRenderer(DocumentPixelFilters(), TestDispatchers)
    private lateinit var source: ImageInfo
    private lateinit var out: File

    @Before
    fun setUp() {
        val file = markedBitmap(400, 300).writeTo(File(temp.root, "pages/k/source.jpg"))
        source = ImageInfo(file.absolutePath, 400, 300, file.length(), "image/jpeg")
        out = File(temp.root, "pages/k/render.jpg")
    }

    private fun decoded(info: ImageInfo): Bitmap = BitmapFactory.decodeFile(info.path)

    @Test
    fun identityKeepsSizeAndPixels() = runTest {
        val info = renderer.render(source, PageEdits(), RenderSpec(maxLongEdgePx = null), out)
        assertThat(info).isEqualTo(ImageInfo(out.absolutePath, 400, 300, out.length(), "image/jpeg"))
        val bitmap = decoded(info)
        assertThat(isReddish(bitmap.getPixel(10, 10))).isTrue()
        assertThat(isReddish(bitmap.getPixel(390, 290))).isFalse()
    }

    @Test
    fun rotationsTurnClockwise() = runTest {
        // Marker starts top-left; after a clockwise quarter turn it sits top-right, and so on.
        val expected = mapOf(
            Rotation.R90 to Triple(300, 400, Pair(290, 10)),
            Rotation.R180 to Triple(400, 300, Pair(390, 290)),
            Rotation.R270 to Triple(300, 400, Pair(10, 390)),
        )
        for ((rotation, want) in expected) {
            for (filter in listOf(PageFilter.ORIGINAL, PageFilter.AUTO)) {
                val info = renderer.render(source, PageEdits(rotation = rotation, filter = filter), RenderSpec(null), out)
                val bitmap = decoded(info)
                assertWithMessage("$rotation $filter size").that(bitmap.width to bitmap.height).isEqualTo(want.first to want.second)
                assertWithMessage("$rotation $filter marker").that(isReddish(bitmap.getPixel(want.third.first, want.third.second))).isTrue()
                assertWithMessage("$rotation $filter old corner").that(isReddish(bitmap.getPixel(10, 10))).isFalse()
            }
        }
    }

    @Test
    fun scalesDownToTheLongEdgeAndNeverUp() = runTest {
        val small = renderer.render(source, PageEdits(), RenderSpec(maxLongEdgePx = 200), out)
        assertThat(small.widthPx to small.heightPx).isEqualTo(200 to 150)
        val same = renderer.render(source, PageEdits(), RenderSpec(maxLongEdgePx = 4000), out)
        assertThat(same.widthPx to same.heightPx).isEqualTo(400 to 300)
    }

    @Test
    fun blackWhitePngIsPureBlackAndWhite() = runTest {
        val png = File(temp.root, "pages/k/render.png")
        val info = renderer.render(source, PageEdits(filter = PageFilter.BLACK_WHITE), RenderSpec(null, ImageFormat.PNG), png)
        assertThat(info.mime).isEqualTo("image/png")
        val bitmap = decoded(info)
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertThat(pixels.toSet()).containsExactly(-0x1000000, -0x1)
        assertThat(bitmap.getPixel(10, 10)).isEqualTo(-0x1000000)
        assertThat(bitmap.getPixel(390, 290)).isEqualTo(-0x1)
    }

    @Test
    fun cropKeepsTheChosenRegion() = runTest {
        val rightHalf = Quad(PointN(0.5f, 0f), PointN(1f, 0f), PointN(1f, 1f), PointN(0.5f, 1f))
        val info = renderer.render(source, PageEdits(crop = rightHalf), RenderSpec(null), out)
        assertThat(info.widthPx to info.heightPx).isEqualTo(200 to 300)
        val bitmap = decoded(info)
        assertThat(isReddish(bitmap.getPixel(5, 5))).isFalse()

        val topLeftCorner = Quad(PointN(0f, 0f), PointN(0.1f, 0f), PointN(0.1f, 0.1f), PointN(0f, 0.1f))
        val corner = renderer.render(source, PageEdits(crop = topLeftCorner, rotation = Rotation.R90), RenderSpec(null), out)
        assertThat(corner.widthPx to corner.heightPx).isEqualTo(30 to 40)
        assertThat(isReddish(decoded(corner).getPixel(15, 20))).isTrue()
    }

    @Test
    fun cropDecodesOnlyAsLargeAsTheOutputNeeds() = runTest {
        val leftQuarter = Quad(PointN(0f, 0f), PointN(0.25f, 0f), PointN(0.25f, 1f), PointN(0f, 1f))
        val info = renderer.render(source, PageEdits(crop = leftQuarter), RenderSpec(maxLongEdgePx = 150), out)
        assertThat(info.widthPx to info.heightPx).isEqualTo(50 to 150)
    }

    @Test
    fun previewIsABitmapWithinTheLimit() = runTest {
        val preview = renderer.preview(source, PageEdits(filter = PageFilter.GRAYSCALE), maxLongEdgePx = 100)
        assertThat(preview.width to preview.height).isEqualTo(100 to 75)
        val c = preview.getPixel(50, 60)
        assertThat(red(c)).isEqualTo(blue(c))
    }

    @Test
    fun thumbnailIs480OnTheLongEdgeAtMost() = runTest {
        val big = markedBitmap(1000, 800).writeTo(File(temp.root, "big.jpg"))
        val bigInfo = ImageInfo(big.absolutePath, 1000, 800, big.length(), "image/jpeg")
        val thumb = renderer.thumbnail(bigInfo, File(temp.root, "pages/k/thumb.jpg"))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(thumb.path, options)
        assertThat(options.outWidth to options.outHeight).isEqualTo(480 to 384)
        assertThat(options.outMimeType).isEqualTo("image/jpeg")

        val small = renderer.thumbnail(source, File(temp.root, "pages/k/thumb-small.jpg"), maxLongEdgePx = 480)
        BitmapFactory.decodeFile(small.path, options)
        assertThat(options.outWidth to options.outHeight).isEqualTo(400 to 300)
    }

    @Test
    fun outputCarriesNoMetadataAndIsUpright() = runTest {
        val tagged = fixture("photo_with_gps.jpg")
        val info = renderer.render(ImageInfo(tagged.path, 480, 640, tagged.length(), "image/jpeg"), PageEdits(), RenderSpec(null), out)
        // EXIF orientation 6 is applied on decode: the stored top-left marker ends up top-right.
        assertThat(info.widthPx to info.heightPx).isEqualTo(480 to 640)
        assertThat(isReddish(decoded(info).getPixel(470, 10))).isTrue()
        val exif = ExifInterface(out.path)
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            .isAnyOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
    }

    @Test
    fun writesAtomicallyAndLeavesNoTemporaryFiles() = runTest {
        renderer.render(source, PageEdits(filter = PageFilter.AUTO), RenderSpec(null), out)
        assertThat(out.parentFile!!.list()!!.toSet()).containsExactly("source.jpg", "render.jpg")
    }

    @Test
    fun missingSourceFails() = runTest {
        val gone = source.copy(path = File(temp.root, "nope.jpg").path)
        val error = runCatching { renderer.render(gone, PageEdits(), RenderSpec(null), out) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(out.exists()).isFalse()
    }
}
