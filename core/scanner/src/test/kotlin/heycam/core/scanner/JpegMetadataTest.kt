package heycam.core.scanner

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class JpegMetadataTest {
    private fun strip(jpeg: ByteArray): Pair<ByteArray, JpegMetadata.Info> {
        val out = ByteArrayOutputStream()
        val info = JpegMetadata.strip(jpeg.inputStream(), out)
        return out.toByteArray() to info
    }

    @Test
    fun removesExifXmpIptcAndCommentsButKeepsTheImageBytes() {
        val plain = TestJpegs.plain(320, 200)
        val dirty = TestJpegs.withSegments(
            plain,
            TestJpegs.exif(orientation = 1),
            TestJpegs.segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray()),
            TestJpegs.segment(0xED, "Photoshop 3.0\u0000".toByteArray()),
            TestJpegs.segment(0xFE, "taken at home".toByteArray()),
        )
        val (clean, info) = strip(dirty)
        assertThat(info).isEqualTo(JpegMetadata.Info(width = 320, height = 200, orientation = 1))
        assertThat(TestJpegs.markers(clean)).containsNoneOf(0xE1, 0xED, 0xFE)
        assertThat(TestJpegs.markers(clean)).contains(0xE0) // JFIF stays
        assertThat(TestJpegs.scanData(clean)).isEqualTo(TestJpegs.scanData(plain))
        assertThat(String(clean, Charsets.ISO_8859_1)).doesNotContain("Exif")
    }

    @Test
    fun keepsColourProfileAndAdobeMarkerButNotOtherApp2Data() {
        val icc = TestJpegs.segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001profile".toByteArray(Charsets.ISO_8859_1))
        val flashPix = TestJpegs.segment(0xE2, "FPXR\u0000data".toByteArray())
        val adobe = TestJpegs.segment(0xEE, "Adobe\u0000d\u0000\u0000\u0000\u0000\u0001".toByteArray(Charsets.ISO_8859_1))
        val (clean, _) = strip(TestJpegs.withSegments(TestJpegs.plain(10, 10), icc, flashPix, adobe))
        val text = String(clean, Charsets.ISO_8859_1)
        assertThat(text).contains("ICC_PROFILE")
        assertThat(text).contains("Adobe")
        assertThat(text).doesNotContain("FPXR")
    }

    @Test
    fun readsTheOrientationInBothByteOrders() {
        assertThat(strip(TestJpegs.withSegments(TestJpegs.plain(10, 20), TestJpegs.exif(6))).second.orientation).isEqualTo(6)
        assertThat(strip(TestJpegs.withSegments(TestJpegs.plain(10, 20), TestJpegs.exif(3, bigEndian = false))).second.orientation)
            .isEqualTo(3)
        assertThat(strip(TestJpegs.withSegments(TestJpegs.plain(10, 20), TestJpegs.exif(42))).second.orientation).isEqualTo(1)
    }

    @Test
    fun leavesBehindWhatAMotionPhotoOrMakerAppendsAfterTheImage() {
        val plain = TestJpegs.plain(64, 48)
        val xmp = TestJpegs.segment(
            0xE1,
            "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta GCamera:MotionPhoto=\"1\"/>".toByteArray(),
        )
        val video = "\u0000\u0000\u0000\u0018ftypmp42 moov gps 12.9716 77.5946".toByteArray(Charsets.ISO_8859_1)
        val samsung = "SEFHtrailer\u00FF\u00D8\u00FF\u00E1Exif".toByteArray(Charsets.ISO_8859_1)
        val motionPhoto = TestJpegs.withSegments(plain, TestJpegs.exif(orientation = 1), xmp) + video + samsung

        val (clean, info) = strip(motionPhoto)

        assertThat(info).isEqualTo(JpegMetadata.Info(width = 64, height = 48, orientation = 1))
        assertThat(clean).isEqualTo(strip(plain).first)
        assertThat(clean.copyOfRange(clean.size - 2, clean.size)).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val text = String(clean, Charsets.ISO_8859_1)
        listOf("ftyp", "gps", "SEFH", "Exif", "MotionPhoto").forEach { assertThat(text).doesNotContain(it) }
    }

    @Test
    fun keepsEveryScanOfAProgressiveJpegAndFiltersTheSegmentsBetweenThem() {
        val progressive = TestJpegs.encode(TestJpegs.noise(600, 400), progressive = true)
        val scans = TestJpegs.markerOffsets(progressive, 0xDA)
        assertThat(scans.size).isGreaterThan(1)
        assertThat(progressive.size).isGreaterThan(64 * 1024) // crosses the reader's buffer
        val between = TestJpegs.insertAt(
            progressive,
            scans[1],
            TestJpegs.segment(0xFE, "between scans".toByteArray()),
            TestJpegs.exif(orientation = 1),
        )

        val (clean, info) = strip(between)

        assertThat(info).isEqualTo(JpegMetadata.Info(width = 600, height = 400, orientation = 1))
        assertThat(clean).isEqualTo(progressive) // nothing else to remove: byte for byte
        assertThat(TestJpegs.markerOffsets(clean, 0xDA)).hasSize(scans.size)
        assertThat(String(clean, Charsets.ISO_8859_1)).doesNotContain("between scans")
        assertThat(TestJpegs.pixels(clean)).isEqualTo(TestJpegs.pixels(progressive))
    }

    @Test
    fun copiesRestartMarkersAndStuffedBytesAsTheyAre() {
        val restarts = TestJpegs.encode(TestJpegs.noise(160, 96), restartInterval = 2)
        assertThat(TestJpegs.markerOffsets(restarts, 0xD0)).isNotEmpty()
        assertThat(TestJpegs.markerOffsets(restarts, 0x00)).isNotEmpty()

        val clean = ByteArrayOutputStream().also {
            JpegMetadata.strip(TestJpegs.OneByteAtATime(restarts.inputStream()), it)
        }.toByteArray()

        assertThat(clean).isEqualTo(restarts)
        assertThat(TestJpegs.pixels(clean)).isEqualTo(TestJpegs.pixels(restarts))
    }

    @Test
    fun dropsFillBytesBeforeMarkersAndClosesAnImageThatEndsEarly() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        val head = bytes(0xFF, 0xD8) +
            TestJpegs.segment(0xC0, bytes(8, 0, 2, 0, 3, 1, 1, 0x11, 0)) +
            TestJpegs.segment(0xDA, bytes(1, 1, 0, 0, 63, 0))
        val scan = bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF, 0xFF, 0xFF, 0xD1, 0x78)
        val cleanScan = bytes(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF, 0xD1, 0x78)
        val eoi = bytes(0xFF, 0xD9)

        val (whole, info) = strip(head + scan + bytes(0xFF, 0xFF) + eoi + "trailer".toByteArray())
        assertThat(whole).isEqualTo(head + cleanScan + eoi)
        assertThat(info).isEqualTo(JpegMetadata.Info(width = 3, height = 2, orientation = 1))

        assertThat(strip(head + scan).first).isEqualTo(head + cleanScan + eoi)
        assertThat(strip(head + scan + bytes(0xFF)).first).isEqualTo(head + cleanScan + eoi)
    }

    @Test
    fun rejectsWhatIsNotAWholeJpeg() {
        assertThrows(IOException::class.java) { strip("PNG not jpeg".toByteArray()) }
        assertThrows(IOException::class.java) { strip(TestJpegs.plain(10, 10).copyOf(40)) }
        assertThrows(IOException::class.java) { strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())) }
    }
}
