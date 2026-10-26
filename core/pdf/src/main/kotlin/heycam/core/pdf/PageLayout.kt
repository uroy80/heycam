package heycam.core.pdf

import heycam.core.model.Margin
import heycam.core.model.PageSize

/** Where one page image sits on its PDF page, in points with the PDF origin at the bottom-left. */
internal data class Placement(
    val pageWidth: Float,
    val pageHeight: Float,
    val imageX: Float,
    val imageY: Float,
    val imageWidth: Float,
    val imageHeight: Float,
)

internal object PageLayout {
    /** A4 height; the long edge of a FIT page. */
    const val FIT_LONG_EDGE_PT = 841.89f

    /**
     * PLAN 3.10: FIT takes the image's aspect ratio with the long edge at 841.89 pt; A4, Letter and Legal turn
     * landscape when the image is wider than tall. The margin is applied on every side and the image is fitted and
     * centred inside what is left.
     */
    fun place(pageSize: PageSize, margin: Margin, imageWidthPx: Int, imageHeightPx: Int): Placement {
        require(imageWidthPx > 0 && imageHeightPx > 0) { "image size must be positive" }
        val landscape = imageWidthPx > imageHeightPx
        val (pageW, pageH) = when (pageSize) {
            PageSize.FIT -> if (landscape) {
                FIT_LONG_EDGE_PT to FIT_LONG_EDGE_PT * imageHeightPx / imageWidthPx
            } else {
                FIT_LONG_EDGE_PT * imageWidthPx / imageHeightPx to FIT_LONG_EDGE_PT
            }
            else -> {
                val short = minOf(pageSize.widthPt, pageSize.heightPt)
                val long = maxOf(pageSize.widthPt, pageSize.heightPt)
                if (landscape) long to short else short to long
            }
        }
        // A margin can never eat the whole page: keep at least half of each side for the image.
        val inset = minOf(margin.pt, pageW / 4f, pageH / 4f)
        val boxW = pageW - 2 * inset
        val boxH = pageH - 2 * inset
        val scale = minOf(boxW / imageWidthPx, boxH / imageHeightPx)
        val drawW = imageWidthPx * scale
        val drawH = imageHeightPx * scale
        return Placement(
            pageWidth = pageW,
            pageHeight = pageH,
            imageX = (pageW - drawW) / 2f,
            imageY = (pageH - drawH) / 2f,
            imageWidth = drawW,
            imageHeight = drawH,
        )
    }
}
