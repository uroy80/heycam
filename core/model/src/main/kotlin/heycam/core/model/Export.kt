package heycam.core.model

/** MVP formats. v1.1 adds ZIP, DOCX (text only), LONG_IMAGE. */
enum class ExportFormat { PDF, JPEG, PNG, TEXT }

/** Starting values; :core:imaging tunes them by measurement in M1 and records the numbers in docs/export-presets.txt. */
enum class SizePreset(val maxLongEdgePx: Int?, val jpegQuality: Int) {
    SMALL(1600, 60), STANDARD(2400, 80), ORIGINAL(null, 92),
}

/** FIT = page takes the image's aspect ratio with the long edge at 841.89 pt (A4 height). */
enum class PageSize(val widthPt: Float, val heightPt: Float) {
    FIT(0f, 0f), A4(595.28f, 841.89f), LETTER(612f, 792f), LEGAL(612f, 1008f),
}

enum class Margin(val pt: Float) { NONE(0f), NARROW(18f), NORMAL(36f) }

/** Never logged, never persisted, never put in a data class's toString(). Call [wipe] when done. */
class Password(private val chars: CharArray) {
    fun chars(): CharArray = chars.copyOf()
    fun wipe() = chars.fill('\u0000')
    override fun toString() = "Password(***)"
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val size: SizePreset = SizePreset.STANDARD,
    val pageSize: PageSize = PageSize.FIT,
    val margin: Margin = Margin.NONE,
    val searchable: Boolean = true,   // PDF: invisible text layer
    val pages: List<PageId>? = null,  // subset, null = all
    val fileName: String? = null,     // null = sanitised title
    val password: Password? = null,   // v1.1
    val pageNumbers: Boolean = false, // v1.1
    val watermark: String? = null,    // v1.1
)
