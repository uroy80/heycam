package heycam.core.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CidAllocatorTest {
    @Test
    fun sameCharacterKeepsItsCid() {
        val cids = CidAllocator()
        assertThat(cids.encode("abca").toList()).containsExactly(1, 2, 3, 1).inOrder()
        assertThat(cids.encode("cab").toList()).containsExactly(3, 1, 2).inOrder()
        assertThat(cids.size).isEqualTo(3)
    }

    @Test
    fun characterOutsideTheBmpIsOneCidMappedToASurrogatePair() {
        val cids = CidAllocator()
        val codes = cids.encode("a𠮷") // U+20BB7
        assertThat(codes.toList()).containsExactly(1, 2).inOrder()
        assertThat(cids.toUnicodeCMap()).contains("<0002> <D842DFB7>")
    }

    @Test
    fun toUnicodeHasOneBfcharEntryPerCidInBlocksOfAHundred() {
        val cids = CidAllocator()
        cids.encode((0 until 250).map { 'A' + it }.joinToString(""))
        val cmap = cids.toUnicodeCMap()
        assertThat(Regex("""(\d+) beginbfchar""").findAll(cmap).map { it.groupValues[1].toInt() }.toList())
            .containsExactly(100, 100, 50).inOrder()
        assertThat(cmap).contains("<0001> <0041>")
        assertThat(cmap).contains("<00FA> <013A>")
        assertThat(cmap).contains("<0000> <FFFF>")
    }

    @Test
    fun cidToGidMapsEveryCidToTheOneGlyph() {
        val cids = CidAllocator()
        cids.encode("xyz")
        val map = cids.cidToGidMap()
        assertThat(map.size).isEqualTo(8)
        for (cid in 0..3) {
            assertThat(map[2 * cid].toInt()).isEqualTo(0)
            assertThat(map[2 * cid + 1].toInt()).isEqualTo(1)
        }
    }

    @Test
    fun advancesFollowTheCharactersNaturalWidth() {
        assertThat(CidAllocator.advanceOfCodePoint('a'.code)).isEqualTo(500)
        assertThat(CidAllocator.advanceOfCodePoint('न'.code)).isEqualTo(500)
        assertThat(CidAllocator.advanceOfCodePoint(' '.code)).isEqualTo(250)
        assertThat(CidAllocator.advanceOfCodePoint('東'.code)).isEqualTo(1000)
        assertThat(CidAllocator.advanceOfCodePoint('で'.code)).isEqualTo(1000)
        assertThat(CidAllocator.advanceOfCodePoint('안'.code)).isEqualTo(1000)
        assertThat(CidAllocator.advanceOfCodePoint('、'.code)).isEqualTo(1000)
        assertThat(CidAllocator.advanceOfCodePoint(0x3000)).isEqualTo(1000) // ideographic space
        assertThat(CidAllocator.advanceOfCodePoint(0xFF21)).isEqualTo(1000) // full-width A
        assertThat(CidAllocator.advanceOfCodePoint(0xFF76)).isEqualTo(500) // half-width katakana
        assertThat(CidAllocator.advanceOfCodePoint(0x20BB7)).isEqualTo(1000)
    }

    @Test
    fun widthRunsCoverOnlyNonDefaultWidths() {
        val cids = CidAllocator()
        val codes = cids.encode("ab 東京c駅")
        assertThat(cids.ems(codes)).isWithin(0.0001f).of(0.5f + 0.5f + 0.25f + 1f + 1f + 0.5f + 1f)
        assertThat(cids.widthRuns()).containsExactly(3 to listOf(250, 1000, 1000), 7 to listOf(1000)).inOrder()
    }

    @Test
    fun runningOutOfCidsFallsBackToTheReplacementCharacter() {
        val cids = CidAllocator(maxCid = 4)
        val codes = cids.encode("abcdef")
        assertThat(codes.toList()).containsExactly(1, 2, 3, 4, 4, 4).inOrder()
        assertThat(cids.toUnicodeCMap()).contains("<0004> <FFFD>")
    }
}
