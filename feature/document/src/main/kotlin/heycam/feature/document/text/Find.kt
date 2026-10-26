package heycam.feature.document.text

import heycam.core.model.PageId
import java.text.Normalizer

/** One occurrence of the find query: [range] indexes the page's text. */
internal data class FindMatch(val pageId: PageId, val pageIndex: Int, val range: IntRange)

/** The most matches the find bar counts; past this it shows "999+". */
internal const val MAX_FIND_MATCHES = 999

/**
 * Case-insensitive search over the pages' texts in page order (F7 find bar). Texts are stored NFC, so the query
 * is normalised to NFC too; comparison is char by char, so ranges index the original text.
 */
internal fun findMatches(pages: List<Pair<PageId, String?>>, query: String): List<FindMatch> {
    val needle = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<FindMatch>()
    pages.forEachIndexed { pageIndex, (pageId, text) ->
        if (text.isNullOrEmpty()) return@forEachIndexed
        var from = 0
        while (out.size < MAX_FIND_MATCHES) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            out += FindMatch(pageId, pageIndex, at until at + needle.length)
            from = at + needle.length
        }
    }
    return out
}

/** Words in a page's text, for the "412 words" figure. */
internal fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
