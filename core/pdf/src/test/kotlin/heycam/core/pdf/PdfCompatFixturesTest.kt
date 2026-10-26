package heycam.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import heycam.core.model.BoxPx
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.TextLine
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Gate G2 fixtures (PLAN §6.5). Writes searchable PDFs to build/pdf-fixtures with an expected.txt listing
 * "<file>\t<text pdftotext must find>". CI runs qpdf --check and pdftotext over them; the same files go to the
 * manual viewer pass recorded in docs/pdf-compat.txt.
 */
@RunWith(RobolectricTestRunner::class)
class PdfCompatFixturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun writeFixtures() {
        PDFBoxResourceLoader.init(context)
        val service = pdfService(context, tmp.newFolder("staging"))
        val out = File(System.getProperty("heycam.pdfFixtures") ?: "build/pdf-fixtures").apply { mkdirs() }
        val portrait = TestImages.jpeg(tmp.newFile("portrait.jpg"), 1240, 1754)
        val landscape = TestImages.jpeg(tmp.newFile("landscape.jpg"), 1754, 1240)
        fun line(text: String, top: Float, angle: Float = 0f, size: Float = 48f): TextLine {
            val width = naturalWidth(text, size)
            val box = if (angle == 0f) BoxPx(120f, top, 120f + width, top + size) else rotatedBox(120f, top, width, size, angle)
            return TextLine(text, box, angleDeg = angle)
        }

        val fixtures = listOf(
            Fixture("latin.pdf", PageSize.FIT, Margin.NONE, portrait, listOf("Invoice 2026-10", "Total due 4,250.00", "email: billing@example.com")),
            Fixture("hindi.pdf", PageSize.A4, Margin.NARROW, portrait, listOf("नमस्ते, यह एक परीक्षण दस्तावेज़ है।", "धन्यवाद", "Thank you")),
            Fixture("cjk.pdf", PageSize.LETTER, Margin.NORMAL, portrait, listOf("東京駅で会う", "안녕하세요 서울", "こんにちは")),
            Fixture("rotated.pdf", PageSize.LEGAL, Margin.NONE, landscape, listOf("Rotated page", "Second rotated line"), angle = 3f),
            // Long lines a little off straight, as a scan that was not quite square: poppler used to cut them up.
            Fixture(
                "tilted.pdf", PageSize.A4, Margin.NORMAL, portrait,
                listOf("The quick brown fox jumps over the lazy dog again", "Invoice HC-2026-104, total amount due 4,250.00", "Meeting at Tokyo Station, 3 pm"),
                angle = 2.5f, size = 28f,
            ),
            Fixture("non-bmp.pdf", PageSize.A4, Margin.NONE, portrait, listOf("𠮷野家 rice bowl")),
        )
        val expected = StringBuilder()
        for (fixture in fixtures) {
            val (w, h) = if (fixture.image == portrait) 1240 to 1754 else 1754 to 1240
            val lines = fixture.lines.mapIndexed { i, text -> line(text, 200f + 120f * i, fixture.angle, fixture.size) }
            val spec = PdfSpec(
                title = fixture.name.removeSuffix(".pdf"),
                pages = listOf(PdfPage(fixture.image, "image/jpeg", w, h, lines)),
                pageSize = fixture.pageSize,
                margin = fixture.margin,
                textLayer = true,
            )
            val bytes = service.writeBytes(spec)
            File(out, fixture.name).writeBytes(bytes)
            val text = extractText(bytes)
            fixture.lines.forEach { expectedLine ->
                assertThat(text).contains(expectedLine)
                expected.append(fixture.name).append('\t').append(expectedLine).append('\n')
            }
        }
        File(out, "expected.txt").writeText(expected.toString())
    }

    private data class Fixture(
        val name: String,
        val pageSize: PageSize,
        val margin: Margin,
        val image: File,
        val lines: List<String>,
        val angle: Float = 0f,
        val size: Float = 48f,
    )
}
