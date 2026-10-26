package heycam.core.export

import com.google.common.truth.Truth.assertThat
import heycam.core.model.ExportFormat
import org.junit.Test

class FileNamesTest {
    private fun utf8(name: String) = name.toByteArray(Charsets.UTF_8).size

    private companion object {
        const val NAME_MAX = 255 // bytes, on ext4 and f2fs
    }

    @Test
    fun forbiddenAndControlCharactersBecomeDashes() {
        assertThat(FileNames.sanitize("a/b\\c:d*e?f\"g<h>i|j\u0007k", "x")).isEqualTo("a-b-c-d-e-f-g-h-i-j-k")
    }

    @Test
    fun whitespaceCollapsesAndEndsAreTrimmed() {
        assertThat(FileNames.sanitize("  Rent \t\n receipt  Oct  ", "x")).isEqualTo("Rent receipt Oct")
        assertThat(FileNames.sanitize("..hidden.", "x")).isEqualTo("hidden")
    }

    @Test
    fun blankNamesFallBack() {
        assertThat(FileNames.sanitize("   ", "heycam")).isEqualTo("heycam")
        assertThat(FileNames.sanitize("...", "heycam")).isEqualTo("heycam")
    }

    @Test
    fun longNamesAreCutAtAHundredWithoutSplittingAPair() {
        assertThat(FileNames.sanitize("a".repeat(150), "x")).hasLength(100)
        val name = "b".repeat(99) + "😀" + "c"
        val cut = FileNames.sanitize(name, "x")
        assertThat(cut).isEqualTo("b".repeat(99))
    }

    @Test
    fun longNamesInThreeByteScriptsStayUnderTheFileSystemLimit() {
        val title = "किराया रसीद ".repeat(20) // 12 characters, 32 bytes each time
        val base = FileNames.sanitize(title, "x")
        assertThat(utf8(base)).isAtMost(FileNames.MAX_BASE_BYTES)
        assertThat(title).startsWith(base)
        val page100 = FileNames.name(base, ExportFormat.JPEG, 99)
        assertThat(utf8(page100)).isAtMost(NAME_MAX)
        assertThat(utf8(FileNames.unique(page100, setOf(page100)))).isAtMost(NAME_MAX)
        assertThat(utf8(FileNames.sanitize("東".repeat(100), "x"))).isAtMost(FileNames.MAX_BASE_BYTES)
    }

    @Test
    fun theByteLimitNeverSplitsALetterFromItsMarkOrAnEmojiFromItsModifier() {
        // 4 + 6 x 32 = 196 bytes; the next "कि" (6 bytes) would pass 200, and half of it would strand the vowel sign.
        assertThat(FileNames.sanitize("abcd" + "कि".repeat(40), "x")).isEqualTo("abcd" + "कि".repeat(32))
        // 65 x 3 = 195 bytes; 👍🏽 is 8 bytes, and its first half would drop the skin tone.
        assertThat(FileNames.sanitize("東".repeat(65) + "👍🏽", "x")).isEqualTo("東".repeat(65))
    }

    @Test
    fun asciiNamesKeepTheCharacterLimit() {
        val title = "Rent receipt ".repeat(10)
        assertThat(FileNames.sanitize(title, "x")).isEqualTo(title.take(100).trimEnd())
    }

    @Test
    fun scriptsArePreserved() {
        assertThat(FileNames.sanitize("किराया रसीद 東京", "x")).isEqualTo("किराया रसीद 東京")
    }

    @Test
    fun namesCarryTheExtensionAndPageNumber() {
        assertThat(FileNames.name("Lease", ExportFormat.PDF, null)).isEqualTo("Lease.pdf")
        assertThat(FileNames.name("Lease", ExportFormat.JPEG, 0)).isEqualTo("Lease - p01.jpg")
        assertThat(FileNames.name("Lease", ExportFormat.PNG, 11)).isEqualTo("Lease - p12.png")
        assertThat(FileNames.name("Lease", ExportFormat.TEXT, null)).isEqualTo("Lease.txt")
    }

    @Test
    fun uniqueAddsACounterBeforeTheExtension() {
        assertThat(FileNames.unique("a.pdf", emptySet())).isEqualTo("a.pdf")
        assertThat(FileNames.unique("a.pdf", setOf("a.pdf", "a (2).pdf"))).isEqualTo("a (3).pdf")
        assertThat(FileNames.unique("noext", setOf("noext"))).isEqualTo("noext (2)")
    }
}
