package heycam.core.pdf

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JpegTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsSizeAndComponentsWithoutDecoding() {
        val rgb = Jpeg.read(TestImages.jpeg(tmp.newFile("rgb.jpg"), 321, 123))
        assertThat(rgb).isEqualTo(JpegInfo(width = 321, height = 123, components = 3, adobeCmyk = false))
        val gray = Jpeg.read(TestImages.jpeg(tmp.newFile("gray.jpg"), 40, 50, gray = true))
        assertThat(gray.components).isEqualTo(1)
    }

    @Test
    fun skipsMetadataSegmentsBeforeTheFrame() {
        val jpeg = TestImages.jpeg(tmp.newFile("a.jpg"), 64, 32).readBytes()
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 8) + "Exif\u0000\u0000".toByteArray()
        val withExif = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        assertThat(Jpeg.read(ByteArrayInputStream(withExif)).width).isEqualTo(64)
    }

    @Test
    fun rejectsWhatPdfCannotEmbed() {
        val png = TestImages.png(tmp.newFile("a.png"), 10, 10)
        assertThrows(IOException::class.java) { Jpeg.read(png) }
        val truncated = TestImages.jpeg(tmp.newFile("b.jpg"), 10, 10).readBytes().copyOf(20)
        assertThrows(IOException::class.java) { Jpeg.read(ByteArrayInputStream(truncated)) }
    }
}
