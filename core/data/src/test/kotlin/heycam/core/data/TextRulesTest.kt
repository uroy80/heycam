package heycam.core.data

import com.google.common.truth.Truth.assertThat
import heycam.core.model.BoxPx
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PointN
import heycam.core.model.Quad
import heycam.core.model.TextLine
import org.junit.Assert.assertThrows
import org.junit.Test

/** Plain JVM tests of the name, search and storage helpers. */
class TextRulesTest {
    @Test
    fun namesAreCleanedToOneNfcLine() {
        assertThat(Names.clean("  Gas \n\t bill\u0000 2026  ", 200)).isEqualTo("Gas bill 2026")
        assertThat(Names.clean("Café", 200)).isEqualTo("Café")
        assertThrows(IllegalArgumentException::class.java) { Names.clean(" \n\u0007 ", 200) }
    }

    @Test
    fun namesAreCappedWithoutSplittingACharacter() {
        val emoji = "📄" // one code point, two chars
        assertThat(Names.clean(emoji.repeat(5), 3)).isEqualTo(emoji.repeat(3))
        assertThat(Names.clean("abc def", 4)).isEqualTo("abc")
    }

    @Test
    fun searchUsesTrigramsFromThreeCodePoints() {
        assertThat(SearchText.usesFts("ab")).isFalse()
        assertThat(SearchText.usesFts("東京")).isFalse()
        assertThat(SearchText.usesFts("東京駅")).isTrue()
        assertThat(SearchText.usesFts("📄📄")).isFalse() // 2 code points, 4 chars
        assertThat(SearchText.normalize("  Café ")).isEqualTo("Café")
        assertThat(SearchText.ftsPhrase("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
    }

    @Test
    fun snippetsCentreTheFirstMatchOnOneLine() {
        val text = "a".repeat(100) + "\nInvoice\tno 42\n" + "b".repeat(100)
        val snippet = Snippets.around(text, "invoice")
        assertThat(snippet.text).startsWith("…")
        assertThat(snippet.text).endsWith("…")
        assertThat(snippet.text).doesNotContain("\n")
        val match = snippet.matches.single()
        assertThat(snippet.text.substring(match.first, match.last + 1)).isEqualTo("Invoice")
        assertThat(snippet.text.length).isAtMost(1 + 40 + "Invoice".length + 40 + 1)
    }

    @Test
    fun snippetsMarkEveryMatchInTheWindowAndKeepSurrogatePairs() {
        val snippet = Snippets.around("ab ab ab", "ab")
        assertThat(snippet.matches).containsExactly(0..1, 3..4, 6..7).inOrder()

        val emoji = "📄"
        val long = emoji.repeat(30) + "x" + emoji.repeat(30)
        val around = Snippets.around(long, "x")
        val body = around.text.removePrefix("…").removeSuffix("…")
        assertThat(Character.isLowSurrogate(body.first())).isFalse()
        assertThat(Character.isHighSurrogate(body.last())).isFalse()

        val noMatch = Snippets.around("short text", "zzz")
        assertThat(noMatch).isEqualTo(heycam.core.model.Snippet("short text", emptyList()))
    }

    @Test
    fun linesAndCropRoundTripAndDamageIsTolerated() {
        val lines = listOf(TextLine("Café", BoxPx(1f, 2f, 3f, 4f), 5f, 0.5f), TextLine("x", BoxPx(0f, 0f, 1f, 1f)))
        val decoded = Codecs.decodeLines(Codecs.encodeLines(lines))
        assertThat(decoded[0]).isEqualTo(TextLine("Café", BoxPx(1f, 2f, 3f, 4f), 5f, 0.5f))
        assertThat(decoded[1]).isEqualTo(lines[1])
        assertThat(Codecs.decodeLines("{broken")).isEmpty()

        val quad = Quad(PointN(0.1f, 0.2f), PointN(0.9f, 0.1f), PointN(0.95f, 0.9f), PointN(0.05f, 0.95f))
        assertThat(Codecs.decodeCrop(Codecs.encodeCrop(quad))).isEqualTo(quad)
        assertThat(Codecs.encodeCrop(null)).isNull()
        assertThat(Codecs.decodeCrop("[]")).isNull()
    }

    @Test
    fun enumsFromNewerVersionsFallBack() {
        assertThat(enumOr("GRAYSCALE", PageFilter.ORIGINAL)).isEqualTo(PageFilter.GRAYSCALE)
        assertThat(enumOr("SEPIA", PageFilter.ORIGINAL)).isEqualTo(PageFilter.ORIGINAL)
        assertThat(enumOr(null, PageFilter.ORIGINAL)).isEqualTo(PageFilter.ORIGINAL)
        assertThat(enumOrNull<OcrScript>("THAI")).isNull()
        assertThat(rotationOf(270).degrees).isEqualTo(270)
        assertThat(rotationOf(45).degrees).isEqualTo(0)
    }
}
