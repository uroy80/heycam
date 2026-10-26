package heycam.feature.document.text

import com.google.common.truth.Truth.assertThat
import heycam.core.model.PageId
import heycam.feature.document.pages.MoveAction
import heycam.feature.document.pages.destination
import org.junit.Test

class FindTest {
    private val pages = listOf(
        PageId(1) to "The tenant will pay a deposit. The Deposit is held.",
        PageId(2) to null,
        PageId(3) to "No match here",
        PageId(4) to "धन्यवाद deposit",
    )

    @Test
    fun findsEveryMatchInPageOrderIgnoringCase() {
        val matches = findMatches(pages, "deposit")
        assertThat(matches.map { it.pageIndex }).containsExactly(0, 0, 3).inOrder()
        val first = pages[0].second!!
        assertThat(first.substring(matches[0].range)).isEqualTo("deposit")
        assertThat(first.substring(matches[1].range)).isEqualTo("Deposit")
    }

    @Test
    fun blankQueryFindsNothing() {
        assertThat(findMatches(pages, "  ")).isEmpty()
    }

    @Test
    fun queryIsNormalisedToNfc() {
        val nfc = "café menu"
        val decomposed = "café"
        val matches = findMatches(listOf(PageId(1) to nfc), decomposed)
        assertThat(matches).hasSize(1)
        assertThat(nfc.substring(matches[0].range)).isEqualTo("café")
    }

    @Test
    fun findsDevanagari() {
        assertThat(findMatches(pages, "धन्यवाद").single().pageIndex).isEqualTo(3)
    }

    @Test
    fun countsWords() {
        assertThat(wordCount("  one two\nthree\n\nfour ")).isEqualTo(4)
        assertThat(wordCount("")).isEqualTo(0)
    }

    @Test
    fun moveActionsStayInsideTheDocument() {
        assertThat(MoveAction.EARLIER.destination(0, 3)).isNull()
        assertThat(MoveAction.LATER.destination(0, 3)).isEqualTo(1)
        assertThat(MoveAction.START.destination(2, 3)).isEqualTo(0)
        assertThat(MoveAction.START.destination(0, 3)).isNull()
        assertThat(MoveAction.END.destination(0, 3)).isEqualTo(2)
        assertThat(MoveAction.LATER.destination(2, 3)).isNull()
    }
}
