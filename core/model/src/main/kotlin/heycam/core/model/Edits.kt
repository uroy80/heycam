package heycam.core.model

/** Non-destructive page edits; applied by PageRenderer on top of Page.source. */
data class PageEdits(
    val rotation: Rotation = Rotation.R0,
    val filter: PageFilter = PageFilter.ORIGINAL,
    val crop: Quad? = null,                    // v1.1 manual re-crop; normalised corners on the source image
    val adjust: Adjustments = Adjustments.NONE, // v1.1 brightness/contrast
) {
    val isIdentity: Boolean
        get() = rotation == Rotation.R0 && filter == PageFilter.ORIGINAL && crop == null && adjust == Adjustments.NONE

    /** Rotation or crop changes move text boxes, so they invalidate recognised text; filters do not. */
    fun sameGeometryAs(other: PageEdits): Boolean = rotation == other.rotation && crop == other.crop
}

enum class Rotation(val degrees: Int) {
    R0(0), R90(90), R180(180), R270(270);

    fun clockwise(): Rotation = entries[(ordinal + 1) % 4]
}

/** MVP: ORIGINAL ("as scanned"), AUTO, GRAYSCALE, BLACK_WHITE. WHITEBOARD ships in v1.1. */
enum class PageFilter { ORIGINAL, AUTO, GRAYSCALE, BLACK_WHITE, WHITEBOARD }

/** Corners in [0,1] source-image coordinates, clockwise from top-left. */
data class Quad(val topLeft: PointN, val topRight: PointN, val bottomRight: PointN, val bottomLeft: PointN)

data class PointN(val x: Float, val y: Float)

data class Adjustments(val brightness: Float = 0f, val contrast: Float = 0f) { // each in -1..1
    companion object { val NONE = Adjustments() }
}
