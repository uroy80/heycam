package heycam.buildlogic

/**
 * Source rules of PLAN §3.2 and §3.8, checked by every module's `checkImports` task over its shipped sources
 * (tests are not scanned). Both imports and fully qualified uses count; comment lines are skipped.
 */
object ImportRules {
    data class Violation(val line: Int, val message: String)

    private class Rule(val pattern: Regex, val message: String, val appliesTo: (module: String) -> Boolean)

    private const val NO_NETWORK = "heycam's own code makes no network requests (PLAN P1)"

    private val rules = listOf(
        Rule(Regex("""\bjava\.net\.(URL|HttpURLConnection|Socket|ServerSocket|DatagramSocket)\b"""), NO_NETWORK) { true },
        Rule(Regex("""\bjavax\.net\.ssl\.HttpsURLConnection\b"""), NO_NETWORK) { true },
        Rule(Regex("""\b(okhttp3|io\.ktor)\."""), NO_NETWORK) { true },
        Rule(Regex("""\bandroid\.webkit\.WebView\b"""), "no WebView in heycam") { true },
        Rule(Regex("""\bandroid\.util\.Log\b"""), "log through HeycamLog, not android.util.Log (PLAN P3)") { it != ":core:common" },
        Rule(Regex("""^\s*import\s+android\."""), ":core:model has no android.* imports (D19)") { it == ":core:model" },
        Rule(Regex("""\bcom\.google\.mlkit\."""), "ML Kit belongs only in :core:ocr and :core:scanner") {
            it != ":core:ocr" && it != ":core:scanner"
        },
        Rule(Regex("""\bcom\.tom_roush\."""), "PDFBox belongs only in :core:pdf") { it != ":core:pdf" },
        Rule(Regex("""\bandroidx\.(room3?|sqlite)\."""), "Room and SQLite belong only in :core:data") { it != ":core:data" },
    )

    fun violations(module: String, lines: List<String>): List<Violation> {
        val active = rules.filter { it.appliesTo(module) }
        return lines.withIndex()
            .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .flatMap { (index, line) ->
                active.filter { it.pattern.containsMatchIn(line) }.map { Violation(index + 1, it.message) }
            }
    }
}
