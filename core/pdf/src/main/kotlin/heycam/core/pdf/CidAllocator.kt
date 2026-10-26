package heycam.core.pdf

import java.util.Locale

/**
 * Per-document CID allocation for the glyphless Type0 font (Identity-H, two-byte codes).
 *
 * Every distinct Unicode code point gets the next free CID, starting at 1, and the ToUnicode CMap maps each CID back
 * with a bfchar entry. Unlike the "CID = UTF-16 code unit" shortcut this also works for characters outside the Basic
 * Multilingual Plane, which become one CID mapped to a surrogate pair.
 *
 * Each CID also carries an advance width in 1/1000 em ([advanceOf]): half an em by default (the font's DW), a full em
 * for East Asian wide characters and a quarter em for spaces. With one width for everything, Tz has to stretch a space
 * as much as a letter, and in short Latin or Korean lines the gap between words grew wide enough for text extractors
 * (poppler) to split one line into columns.
 *
 * @param maxCid highest usable CID; 0xFFFF for two-byte codes (smaller only in tests).
 */
internal class CidAllocator(private val maxCid: Int = 0xFFFF) {
    private val cidByCodePoint = HashMap<Int, Int>()
    private val codePoints = ArrayList<Int>()

    /** Number of CIDs handed out so far, which is also the highest CID in use. */
    val size: Int get() = codePoints.size

    /** The CIDs for [text], one per code point, allocating new ones as needed. */
    fun encode(text: String): IntArray {
        val out = IntArray(text.codePointCount(0, text.length))
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out[n++] = cidFor(cp)
            i += Character.charCount(cp)
        }
        return out
    }

    private fun cidFor(codePoint: Int): Int {
        cidByCodePoint[codePoint]?.let { return it }
        // The last free CID is kept for U+FFFD; once only it is left, every new character reads back as U+FFFD
        // instead of failing the export.
        if (codePoint != REPLACEMENT && codePoints.size >= maxCid - 1) return cidFor(REPLACEMENT)
        codePoints += codePoint
        return codePoints.size.also { cidByCodePoint[codePoint] = it }
    }

    /** Advance of [cid] in 1/1000 em. */
    fun advanceOf(cid: Int): Int = advanceOfCodePoint(codePoints[cid - 1])

    /** Total advance of [cids] in em. */
    fun ems(cids: IntArray): Float = cids.sumOf { advanceOf(it) } / 1000f

    /**
     * The CIDFont /W array as runs of consecutive CIDs whose width differs from [DEFAULT_ADVANCE]: (first CID, widths).
     * CIDs at the default width are left to DW.
     */
    fun widthRuns(): List<Pair<Int, List<Int>>> {
        val runs = ArrayList<Pair<Int, List<Int>>>()
        var start = -1
        var widths = ArrayList<Int>()
        for (cid in 1..size) {
            val width = advanceOf(cid)
            if (width == DEFAULT_ADVANCE) {
                if (start > 0) runs += start to widths
                start = -1
                continue
            }
            if (start < 0) {
                start = cid
                widths = ArrayList()
            }
            widths += width
        }
        if (start > 0) runs += start to widths
        return runs
    }

    /** ToUnicode CMap (ISO 32000-1, 9.10.3) with bfchar entries in blocks of at most 100. */
    fun toUnicodeCMap(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        codePoints.chunked(BFCHAR_BLOCK).forEachIndexed { block, chunk ->
            append(chunk.size).append(" beginbfchar\n")
            chunk.forEachIndexed { i, cp ->
                append('<').append(hex4(block * BFCHAR_BLOCK + i + 1)).append("> <")
                Character.toChars(cp).forEach { append(hex4(it.code)) }
                append(">\n")
            }
            append("endbfchar\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    /** CIDToGIDMap stream: every CID from 0 to [size] maps to glyph 1, two big-endian bytes each. */
    fun cidToGidMap(): ByteArray = ByteArray(2 * (size + 1)).also { map ->
        for (cid in 0..size) map[2 * cid + 1] = 1
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
        const val DEFAULT_ADVANCE = 500
        const val WIDE_ADVANCE = 1000
        const val SPACE_ADVANCE = 250
        private const val BFCHAR_BLOCK = 100
        private const val IDEOGRAPHIC_SPACE = 0x3000

        fun hex4(value: Int): String = String.format(Locale.ROOT, "%04X", value)

        /** Rough natural widths: what a typical font gives the character, relative to the line it sits in. */
        fun advanceOfCodePoint(codePoint: Int): Int = when {
            codePoint == IDEOGRAPHIC_SPACE -> WIDE_ADVANCE
            Character.isSpaceChar(codePoint) -> SPACE_ADVANCE
            isWide(codePoint) -> WIDE_ADVANCE
            else -> DEFAULT_ADVANCE
        }

        private fun isWide(codePoint: Int): Boolean {
            if (codePoint in 0xFF61..0xFFDC) return false // half-width katakana and hangul
            if (codePoint in 0xFF01..0xFF60 || codePoint in 0xFFE0..0xFFE6) return true // full-width forms
            return when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
                -> true
                else -> Character.UnicodeBlock.of(codePoint) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            }
        }
    }
}
