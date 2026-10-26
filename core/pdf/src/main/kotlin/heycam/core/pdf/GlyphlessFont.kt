package heycam.core.pdf

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The one-glyph TrueType font behind the invisible text layer (PLAN D6, 3.10), generated here instead of being
 * shipped as a binary so every byte is reviewable.
 *
 * Glyph 0 is .notdef and glyph 1 is the glyph every CID maps to through CIDToGIDMap. Both are empty and 500 units
 * wide on a 1000-unit em, the font dictionary's DW; the dictionary's W array gives wide characters and spaces their
 * own advance (CidAllocator), which viewers take over the font program's. Text drawn with it is never painted
 * (render mode 3); viewers only need the advances for selection and the ToUnicode CMap for search and copy.
 * Embedding a real font file keeps strict viewers from substituting a system font.
 */
internal object GlyphlessFont {
    const val UNITS_PER_EM = 1000
    const val ADVANCE = 500
    const val ASCENT = 1000
    const val NAME = "GlyphLessFont"

    /** The complete font file (about 500 bytes); identical on every call. */
    val bytes: ByteArray by lazy { build() }

    private class Table(val tag: String, val data: ByteArray)

    private fun build(): ByteArray {
        val tables = listOf(
            Table("OS/2", os2()), Table("cmap", cmap()), Table("glyf", ByteArray(1)), Table("head", head()),
            Table("hhea", hhea()), Table("hmtx", hmtx()), Table("loca", ByteArray(6)), Table("maxp", maxp()),
            Table("name", name()), Table("post", post()),
        ).sortedBy { it.tag }
        val headerSize = 12 + 16 * tables.size
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        val entrySelector = Integer.highestOneBit(tables.size).countTrailingZeroBits()
        val searchRange = (1 shl entrySelector) * 16
        data.writeInt(0x00010000)
        data.writeShort(tables.size)
        data.writeShort(searchRange)
        data.writeShort(entrySelector)
        data.writeShort(tables.size * 16 - searchRange)
        var offset = headerSize
        for (table in tables) {
            data.writeBytes(table.tag)
            data.writeInt(checksum(table.data))
            data.writeInt(offset)
            data.writeInt(table.data.size)
            offset += padded(table.data.size)
        }
        for (table in tables) {
            data.write(table.data)
            repeat(padded(table.data.size) - table.data.size) { data.writeByte(0) }
        }
        val font = out.toByteArray()
        // head.checkSumAdjustment makes the whole file sum to 0xB1B0AFBA (it was 0 while summing).
        val headOffset = headerSize + tables.takeWhile { it.tag != "head" }.sumOf { padded(it.data.size) }
        val adjustment = (0xB1B0AFBAL - (checksum(font).toLong() and 0xFFFFFFFFL)).toInt()
        for (i in 0 until 4) font[headOffset + 8 + i] = (adjustment ushr (24 - 8 * i)).toByte()
        return font
    }

    private fun padded(size: Int) = (size + 3) and 3.inv()

    /** Sum of the big-endian 32-bit words of [data], zero-padded to a multiple of 4. */
    fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size) {
            var word = 0
            for (b in 0 until 4) word = (word shl 8) or (if (i + b < data.size) data[i + b].toInt() and 0xFF else 0)
            sum += word
            i += 4
        }
        return sum
    }

    private inline fun table(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    private fun head() = table {
        writeInt(0x00010000) // version
        writeInt(0x00010000) // fontRevision
        writeInt(0) // checkSumAdjustment, patched after the file is assembled
        writeInt(0x5F0F3CF5) // magicNumber
        writeShort(0x000B) // flags: baseline at y=0, lsb at x=0, integer ppem
        writeShort(UNITS_PER_EM)
        writeLong(0) // created
        writeLong(0) // modified
        writeShort(0); writeShort(0); writeShort(ADVANCE); writeShort(ASCENT) // xMin yMin xMax yMax
        writeShort(0) // macStyle
        writeShort(3) // lowestRecPPEM
        writeShort(2) // fontDirectionHint
        writeShort(0) // indexToLocFormat: short offsets
        writeShort(0) // glyphDataFormat
    }

    private fun hhea() = table {
        writeInt(0x00010000)
        writeShort(ASCENT) // ascender
        writeShort(0) // descender
        writeShort(0) // lineGap
        writeShort(ADVANCE) // advanceWidthMax
        writeShort(0); writeShort(0); writeShort(0) // minLeftSideBearing, minRightSideBearing, xMaxExtent
        writeShort(1); writeShort(0); writeShort(0) // caretSlopeRise, caretSlopeRun, caretOffset
        repeat(4) { writeShort(0) } // reserved
        writeShort(0) // metricDataFormat
        writeShort(2) // numberOfHMetrics
    }

    private fun hmtx() = table { repeat(2) { writeShort(ADVANCE); writeShort(0) } }

    private fun maxp() = table {
        writeInt(0x00010000)
        writeShort(2) // numGlyphs
        // maxPoints, maxContours, maxCompositePoints, maxCompositeContours, maxZones, maxTwilightPoints, maxStorage,
        // maxFunctionDefs, maxInstructionDefs, maxStackElements, maxSizeOfInstructions, maxComponentElements,
        // maxComponentDepth
        listOf(0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0).forEach { writeShort(it) }
    }

    /** One Windows Unicode format-4 subtable holding only the closing 0xFFFF segment: no character maps to a glyph. */
    private fun cmap() = table {
        writeShort(0); writeShort(1) // version, numTables
        writeShort(3); writeShort(1); writeInt(12) // platform 3, encoding 1, offset
        writeShort(4); writeShort(24); writeShort(0) // format, length, language
        writeShort(2); writeShort(2); writeShort(0); writeShort(0) // segCountX2, searchRange, entrySelector, rangeShift
        writeShort(0xFFFF) // endCode
        writeShort(0) // reservedPad
        writeShort(0xFFFF) // startCode
        writeShort(1) // idDelta
        writeShort(0) // idRangeOffset
    }

    private fun name() = table {
        val records = listOf(1 to NAME, 2 to "Regular", 3 to "heycam:$NAME", 4 to NAME, 5 to "Version 1.0", 6 to NAME)
        val strings = records.map { it.second.toByteArray(Charsets.UTF_16BE) }
        writeShort(0) // format
        writeShort(records.size)
        writeShort(6 + 12 * records.size) // stringOffset
        var offset = 0
        records.forEachIndexed { i, (nameId, _) ->
            writeShort(3); writeShort(1); writeShort(0x0409); writeShort(nameId)
            writeShort(strings[i].size); writeShort(offset)
            offset += strings[i].size
        }
        strings.forEach { write(it) }
    }

    private fun os2() = table {
        writeShort(4) // version
        writeShort(ADVANCE) // xAvgCharWidth
        writeShort(400) // usWeightClass
        writeShort(5) // usWidthClass
        writeShort(0) // fsType: installable embedding
        // subscript x/y size and offset, superscript x/y size and offset, strikeout size and position
        listOf(650, 600, 0, 75, 650, 600, 0, 350, 50, 300).forEach { writeShort(it) }
        writeShort(0) // sFamilyClass
        write(ByteArray(10)) // panose
        repeat(4) { writeInt(0) } // ulUnicodeRange1-4
        writeBytes("NONE") // achVendID
        writeShort(0x0040) // fsSelection: REGULAR
        writeShort(0xFFFF); writeShort(0xFFFF) // usFirstCharIndex, usLastCharIndex (cmap maps nothing)
        writeShort(ASCENT); writeShort(0); writeShort(0) // sTypoAscender, sTypoDescender, sTypoLineGap
        writeShort(ASCENT); writeShort(0) // usWinAscent, usWinDescent
        writeInt(0); writeInt(0) // ulCodePageRange1-2
        writeShort(ADVANCE); writeShort(ASCENT) // sxHeight, sCapHeight
        writeShort(0); writeShort(32); writeShort(0) // usDefaultChar, usBreakChar, usMaxContext
    }

    private fun post() = table {
        writeInt(0x00030000) // format 3: no glyph names
        writeInt(0) // italicAngle
        writeShort(-100); writeShort(50) // underlinePosition, underlineThickness
        writeInt(1) // isFixedPitch
        repeat(4) { writeInt(0) } // min/max memory hints
    }
}
