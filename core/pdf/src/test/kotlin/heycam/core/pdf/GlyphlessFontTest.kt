package heycam.core.pdf

import com.google.common.truth.Truth.assertThat
import com.tom_roush.fontbox.ttf.TTFParser
import java.io.ByteArrayInputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlyphlessFontTest {
    @Test
    fun isAValidTwoGlyphTrueTypeFont() {
        val font = TTFParser(true).parse(ByteArrayInputStream(GlyphlessFont.bytes))
        assertThat(font.numberOfGlyphs).isEqualTo(2)
        assertThat(font.unitsPerEm).isEqualTo(1000)
        assertThat(font.getAdvanceWidth(0)).isEqualTo(500)
        assertThat(font.getAdvanceWidth(1)).isEqualTo(500)
        assertThat(font.name).isEqualTo(GlyphlessFont.NAME)
        assertThat(font.header.magicNumber).isEqualTo(0x5F0F3CF5)
    }

    @Test
    fun wholeFileChecksumMatchesTheMagicConstant() {
        assertThat(GlyphlessFont.checksum(GlyphlessFont.bytes)).isEqualTo(0xB1B0AFBA.toInt())
        assertThat(GlyphlessFont.bytes.size % 4).isEqualTo(0)
    }

    @Test
    fun everyTableChecksumIsCorrect() {
        val bytes = GlyphlessFont.bytes
        fun u32(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        val tables = (bytes[4].toInt() shl 8) or (bytes[5].toInt() and 0xFF)
        for (i in 0 until tables) {
            val record = 12 + 16 * i
            val tag = String(bytes, record, 4, Charsets.US_ASCII)
            val offset = u32(record + 8)
            val length = u32(record + 12)
            val data = bytes.copyOfRange(offset, offset + length).also { if (tag == "head") it.fill(0, 8, 12) }
            assertThat(GlyphlessFont.checksum(data)).isEqualTo(u32(record + 4))
        }
    }
}
