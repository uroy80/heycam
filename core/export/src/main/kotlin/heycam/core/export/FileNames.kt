package heycam.core.export

import heycam.core.model.ExportFormat
import java.text.BreakIterator
import java.util.Locale

/** File names for exports (PLAN 3.12). Pure, so the rules unit-test on the JVM. */
internal object FileNames {
    const val MAX_BASE_LENGTH = 100

    /**
     * File systems count a name in UTF-8 bytes (255 on ext4 and f2fs), and Devanagari or CJK letters take three each.
     * 200 leaves room for " - p100", " (99)" and the extension.
     */
    const val MAX_BASE_BYTES = 200

    private val FORBIDDEN = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
    private val SPACES = Regex("""[\s\p{Zs}]+""")

    /**
     * / \ : * ? " < > | and control characters become "-", runs of whitespace one space, ends trimmed (also of
     * dots, which some file systems hide or drop), at most [MAX_BASE_LENGTH] characters and [MAX_BASE_BYTES] UTF-8
     * bytes, cut between whole characters as people see them (a letter with its marks, an emoji with its modifier).
     * Blank results, and a first character that alone is over the limits, fall back to [fallback].
     */
    fun sanitize(name: String, fallback: String): String {
        val cleaned = name.replace(SPACES, " ").replace(FORBIDDEN, "-").trim().trim('.').trim()
        if (cleaned.isEmpty()) return fallback
        if (cleaned.length <= MAX_BASE_LENGTH && utf8Length(cleaned, 0, cleaned.length) <= MAX_BASE_BYTES) return cleaned
        val clusters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(cleaned) }
        var end = 0
        var bytes = 0
        var next = clusters.next()
        while (next != BreakIterator.DONE && next <= MAX_BASE_LENGTH) {
            bytes += utf8Length(cleaned, end, next)
            if (bytes > MAX_BASE_BYTES) break
            end = next
            next = clusters.next()
        }
        return cleaned.substring(0, end).trimEnd().ifEmpty { fallback }
    }

    /** UTF-8 bytes of [text] from [start] to [end]; a lone surrogate counts as the three-byte replacement character. */
    private fun utf8Length(text: String, start: Int, end: Int): Int {
        var bytes = 0
        var i = start
        while (i < end) {
            val codePoint = text.codePointAt(i)
            bytes += when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(codePoint)
        }
        return bytes
    }

    /** "<base>.pdf", or "<base> - p01.jpg" for page [index] (0-based) of a per-page format. */
    fun name(base: String, format: ExportFormat, index: Int?): String {
        val page = if (index != null) String.format(Locale.ROOT, " - p%02d", index + 1) else ""
        return base + page + extension(format)
    }

    fun extension(format: ExportFormat): String = when (format) {
        ExportFormat.PDF -> ".pdf"
        ExportFormat.JPEG -> ".jpg"
        ExportFormat.PNG -> ".png"
        ExportFormat.TEXT -> ".txt"
    }

    fun mime(format: ExportFormat): String = when (format) {
        ExportFormat.PDF -> "application/pdf"
        ExportFormat.JPEG -> "image/jpeg"
        ExportFormat.PNG -> "image/png"
        ExportFormat.TEXT -> "text/plain"
    }

    /** [name] made unique among [taken] with " (2)", " (3)"... before the extension. */
    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 2
        while (true) {
            val candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
            if (candidate !in taken) return candidate
            n++
        }
    }
}
