package heycam.core.ocr

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.vision.text.Text
import heycam.core.model.BoxPx
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextMappingTest {
    // ML Kit's public constructor takes confidence before angle.
    private fun line(text: String, box: Rect, angle: Float = 0f, confidence: Float = 0.9f) =
        Text.Line(text, box, emptyList<Point>(), "und", Matrix(), emptyList<Text.Element>(), confidence, angle)

    private fun block(vararg lines: Text.Line) =
        Text.TextBlock(lines.joinToString("\n") { it.text }, Rect(), emptyList<Point>(), "und", Matrix(), lines.toList())

    @Test
    fun linesKeepBoxAngleAndConfidenceScaledToTheFullImage() {
        val result = Text(
            "",
            listOf(block(line("Invoice", Rect(10, 20, 110, 40), angle = 1.5f, confidence = 0.8f))),
        )
        val page = TextMapping.map(result, scaleX = 2f, scaleY = 3f)
        val only = page.lines.single()
        assertThat(only.text).isEqualTo("Invoice")
        assertThat(only.box).isEqualTo(BoxPx(20f, 60f, 220f, 120f))
        assertThat(only.angleDeg).isEqualTo(1.5f)
        assertThat(only.confidence).isEqualTo(0.8f)
    }

    @Test
    fun linesJoinWithNewlinesAndBlocksWithBlankLines() {
        val result = Text(
            "",
            listOf(
                block(line("नमस्ते", Rect(0, 0, 10, 10)), line("धन्यवाद", Rect(0, 20, 10, 30))),
                block(line("Thank you", Rect(0, 50, 10, 60))),
            ),
        )
        assertThat(TextMapping.map(result, 1f, 1f).text).isEqualTo("नमस्ते\nधन्यवाद\n\nThank you")
    }

    @Test
    fun textIsNfcAndTrimmed() {
        val result = Text("", listOf(block(line("  Café ", Rect(0, 0, 10, 10)))))
        assertThat(TextMapping.map(result, 1f, 1f).lines.single().text).isEqualTo("Café")
    }

    @Test
    fun linesWithoutTextOrAnAreaAreDropped() {
        val result = Text(
            "",
            listOf(
                block(line("   ", Rect(0, 0, 10, 10)), line("No box", Rect()), line("Empty box", Rect(5, 5, 5, 9))),
                block(line("Kept", Rect(0, 0, 10, 10), confidence = -1f)),
            ),
        )
        val page = TextMapping.map(result, 1f, 1f)
        assertThat(page.text).isEqualTo("Kept")
        assertThat(page.lines.single().confidence).isNull()
    }

    @Test
    fun decodeSizeCapsTheLongEdge() {
        assertThat(TextMapping.decodeSize(2480, 3508)).isEqualTo(2121 to 3000)
        assertThat(TextMapping.decodeSize(4000, 1000)).isEqualTo(3000 to 750)
        assertThat(TextMapping.decodeSize(1240, 1754)).isEqualTo(1240 to 1754)
    }

    @Test
    fun engineNamesTheArtifactVersion() {
        assertThat(MlKitOcrEngine.engine(heycam.core.model.OcrScript.LATIN)).isEqualTo("mlkit-text-v2/19.0.1")
        assertThat(MlKitOcrEngine.engine(heycam.core.model.OcrScript.KOREAN)).isEqualTo("mlkit-text-v2/16.0.1")
    }
}
