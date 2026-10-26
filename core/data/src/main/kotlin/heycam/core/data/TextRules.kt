package heycam.core.data

import heycam.core.model.Snippet

/** Cleans user-typed names: NFC, no control characters, single spaces, capped without splitting a character. */
internal object Names {
    const val MAX_TITLE = 200
    const val MAX_FOLDER = 80
    private val SPACES = Regex("\\s+")

    fun clean(raw: String, maxLength: Int): String {
        val oneLine = raw.nfc().map { if (it.isISOControl()) ' ' else it }.joinToString("")
        val collapsed = oneLine.replace(SPACES, " ").trim()
        require(collapsed.isNotEmpty()) { "name is blank" }
        return collapsed.takeCodePoints(maxLength).trimEnd()
    }

    private fun String.takeCodePoints(max: Int): String =
        if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))
}

/** PLAN 3.6 search rules: NFC query; FTS5 trigram phrase for 3+ code points, substring scan below that. */
internal object SearchText {
    private const val MIN_TRIGRAM = 3

    fun normalize(query: String): String = query.nfc().trim()

    fun usesFts(needle: String): Boolean = needle.codePointCount(0, needle.length) >= MIN_TRIGRAM

    /** A quoted FTS5 phrase: every character is literal; embedded quotes are doubled. */
    fun ftsPhrase(needle: String): String = "\"" + needle.replace("\"", "\"\"") + "\""
}

internal object Snippets {
    private const val RADIUS = 40
    private const val ELLIPSIS = "…"

    /** About [RADIUS] characters either side of the first match, on one line, with every match in the window marked. */
    fun around(text: String, needle: String): Snippet {
        val at = text.indexOf(needle, ignoreCase = true)
        if (at < 0) {
            val end = safeEnd(text, minOf(text.length, RADIUS * 2))
            return Snippet(oneLine(text.substring(0, end)) + if (end < text.length) ELLIPSIS else "", emptyList())
        }
        val start = safeStart(text, (at - RADIUS).coerceAtLeast(0))
        val end = safeEnd(text, (at + needle.length + RADIUS).coerceAtMost(text.length))
        val window = (if (start > 0) ELLIPSIS else "") + oneLine(text.substring(start, end)) + (if (end < text.length) ELLIPSIS else "")
        return Snippet(window, matches(window, needle))
    }

    private fun matches(window: String, needle: String): List<IntRange> {
        val found = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val i = window.indexOf(needle, from, ignoreCase = true)
            if (i < 0) break
            found += i until i + needle.length
            from = i + needle.length
        }
        return found
    }

    // Same-length replacement keeps match offsets valid.
    private fun oneLine(s: String) = s.map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.joinToString("")

    private fun safeStart(text: String, i: Int) = if (i > 0 && text[i].isLowSurrogate()) i - 1 else i

    private fun safeEnd(text: String, i: Int) = if (i in 1 until text.length && text[i - 1].isHighSurrogate()) i + 1 else i
}
