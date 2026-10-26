package heycam.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelTest {
    private val square = Quad(PointN(0f, 0f), PointN(1f, 0f), PointN(1f, 1f), PointN(0f, 1f))

    @Test
    fun editsAreIdentityOnlyWithNothingChanged() {
        assertThat(PageEdits().isIdentity).isTrue()
        assertThat(PageEdits(rotation = Rotation.R90).isIdentity).isFalse()
        assertThat(PageEdits(filter = PageFilter.AUTO).isIdentity).isFalse()
        assertThat(PageEdits(crop = square).isIdentity).isFalse()
        assertThat(PageEdits(adjust = Adjustments(brightness = 0.1f)).isIdentity).isFalse()
    }

    @Test
    fun onlyRotationAndCropChangeGeometry() {
        val base = PageEdits(filter = PageFilter.GRAYSCALE)
        assertThat(base.sameGeometryAs(base.copy(filter = PageFilter.BLACK_WHITE, adjust = Adjustments(contrast = 0.5f)))).isTrue()
        assertThat(base.sameGeometryAs(base.copy(rotation = Rotation.R180))).isFalse()
        assertThat(base.sameGeometryAs(base.copy(crop = square))).isFalse()
    }

    @Test
    fun rotationTurnsClockwiseAndWraps() {
        assertThat(Rotation.R0.clockwise()).isEqualTo(Rotation.R90)
        assertThat(Rotation.R270.clockwise()).isEqualTo(Rotation.R0)
        assertThat(Rotation.entries.map { it.degrees }).containsExactly(0, 90, 180, 270).inOrder()
    }

    @Test
    fun boxesScaleAndMeasure() {
        val box = BoxPx(10f, 20f, 110f, 70f)
        assertThat(box.width).isEqualTo(100f)
        assertThat(box.height).isEqualTo(50f)
        assertThat(box.scaled(0.5f, 2f)).isEqualTo(BoxPx(5f, 40f, 55f, 140f))
    }

    @Test
    fun presetsShrinkAndPageSizesArePortrait() {
        assertThat(SizePreset.SMALL.maxLongEdgePx!!).isLessThan(SizePreset.STANDARD.maxLongEdgePx!!)
        assertThat(SizePreset.SMALL.jpegQuality).isLessThan(SizePreset.STANDARD.jpegQuality)
        assertThat(SizePreset.STANDARD.jpegQuality).isLessThan(SizePreset.ORIGINAL.jpegQuality)
        assertThat(SizePreset.ORIGINAL.maxLongEdgePx).isNull()
        for (size in PageSize.entries - PageSize.FIT) assertThat(size.heightPt).isGreaterThan(size.widthPt)
        assertThat(Margin.entries.map { it.pt }).containsExactly(0f, 18f, 36f).inOrder()
    }

    @Test
    fun passwordsNeverPrintAndWipe() {
        val password = Password("hunter2".toCharArray())
        assertThat(password.toString()).isEqualTo("Password(***)")
        assertThat(ExportOptions(password = password).toString()).doesNotContain("hunter2")
        val copy = password.chars()
        password.wipe()
        assertThat(String(copy)).isEqualTo("hunter2")
        assertThat(password.chars().all { it == '\u0000' }).isTrue()
    }

    @Test
    fun defaultsMatchThePlan() {
        val settings = Settings()
        assertThat(settings.trashRetentionDays).isEqualTo(30)
        assertThat(settings.defaultFilter).isEqualTo(PageFilter.ORIGINAL)
        assertThat(settings.exportDefaults).isEqualTo(ExportOptions())
        assertThat(ExportOptions().size).isEqualTo(SizePreset.STANDARD)
        assertThat(ExportOptions().searchable).isTrue()
        assertThat(SearchQuery("x").limit).isEqualTo(50)
    }
}
