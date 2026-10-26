package heycam.core.pdf

import com.google.common.truth.Truth.assertThat
import heycam.core.model.Margin
import heycam.core.model.PageSize
import org.junit.Test

class PageLayoutTest {
    @Test
    fun fitPortraitKeepsTheImageAspectWithTheLongEdgeAtA4Height() {
        val p = PageLayout.place(PageSize.FIT, Margin.NONE, 1000, 2000)
        assertThat(p.pageHeight).isWithin(0.01f).of(841.89f)
        assertThat(p.pageWidth).isWithin(0.01f).of(420.945f)
        assertThat(p.imageX).isWithin(0.01f).of(0f)
        assertThat(p.imageY).isWithin(0.01f).of(0f)
        assertThat(p.imageWidth).isWithin(0.01f).of(p.pageWidth)
    }

    @Test
    fun fitLandscapePutsTheLongEdgeAcross() {
        val p = PageLayout.place(PageSize.FIT, Margin.NONE, 3000, 1500)
        assertThat(p.pageWidth).isWithin(0.01f).of(841.89f)
        assertThat(p.pageHeight).isWithin(0.01f).of(420.945f)
    }

    @Test
    fun paperSizesTurnLandscapeForWideImages() {
        val portrait = PageLayout.place(PageSize.LETTER, Margin.NONE, 800, 1000)
        assertThat(portrait.pageWidth).isEqualTo(612f)
        assertThat(portrait.pageHeight).isEqualTo(792f)
        val landscape = PageLayout.place(PageSize.LEGAL, Margin.NONE, 1000, 800)
        assertThat(landscape.pageWidth).isEqualTo(1008f)
        assertThat(landscape.pageHeight).isEqualTo(612f)
    }

    @Test
    fun imageIsFittedAndCentredInsideTheMargin() {
        // A square image on portrait A4 with a 36 pt margin: width-limited, centred vertically.
        val p = PageLayout.place(PageSize.A4, Margin.NORMAL, 1000, 1000)
        assertThat(p.imageWidth).isWithin(0.01f).of(595.28f - 72f)
        assertThat(p.imageHeight).isWithin(0.01f).of(p.imageWidth)
        assertThat(p.imageX).isWithin(0.01f).of(36f)
        assertThat(p.imageY).isWithin(0.01f).of((841.89f - p.imageHeight) / 2f)
    }

    @Test
    fun fitWithMarginKeepsThePageAndInsetsTheImage() {
        // Equal margins leave a box relatively taller than the image, so the width limits it.
        val p = PageLayout.place(PageSize.FIT, Margin.NARROW, 1000, 2000)
        assertThat(p.pageHeight).isWithin(0.01f).of(841.89f)
        assertThat(p.imageWidth).isWithin(0.01f).of(p.pageWidth - 36f)
        assertThat(p.imageX).isWithin(0.01f).of(18f)
        assertThat(p.imageY + p.imageHeight / 2f).isWithin(0.01f).of(p.pageHeight / 2f)
    }

    @Test
    fun aMarginNeverTakesMoreThanHalfThePage() {
        val p = PageLayout.place(PageSize.FIT, Margin.NORMAL, 4000, 100) // a 21 pt tall strip
        assertThat(p.imageHeight).isGreaterThan(0f)
        assertThat(p.imageWidth).isGreaterThan(0f)
    }
}
