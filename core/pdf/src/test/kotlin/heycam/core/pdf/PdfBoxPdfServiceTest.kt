package heycam.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import heycam.core.model.BoxPx
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.Password
import heycam.core.model.TextLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfBoxPdfServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var service: PdfService
    private lateinit var image: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        service = pdfService(context, tmp.newFolder("staging"))
        image = TestImages.jpeg(tmp.newFile("page.jpg"), WIDTH, HEIGHT)
    }

    private fun page(vararg lines: TextLine, file: File = image, w: Int = WIDTH, h: Int = HEIGHT) =
        PdfPage(file, "image/jpeg", w, h, lines.toList())

    private fun spec(vararg pages: PdfPage, textLayer: Boolean = true, pageSize: PageSize = PageSize.FIT) =
        PdfSpec(title = "Rent receipt", pages = pages.toList(), pageSize = pageSize, margin = Margin.NONE, textLayer = textLayer)

    private fun line(text: String, top: Float, left: Float = 100f, right: Float = 1100f, angle: Float = 0f) =
        TextLine(text, BoxPx(left, top, right, top + 60f), angleDeg = angle)

    @Test
    fun latinDevanagariAndCjkTextCanBeSearchedAndCopied() {
        val pdf = service.writeBytes(
            spec(
                page(line("Invoice 2026-10 total 4,250.00", 300f), line("Thank you", 400f)),
                page(line("नमस्ते, यह एक परीक्षण दस्तावेज़ है।", 300f), line("धन्यवाद", 400f)),
                page(line("東京駅で会う", 300f), line("안녕하세요 서울", 400f), line("こんにちは", 500f)),
            ),
        )
        assertThat(extractText(pdf, page = 1)).contains("Invoice 2026-10 total 4,250.00")
        assertThat(extractText(pdf, page = 1)).contains("Thank you")
        val hindi = extractText(pdf, page = 2)
        assertThat(hindi).contains("धन्यवाद")
        assertThat(hindi).contains("नमस्ते, यह एक परीक्षण दस्तावेज़ है।")
        val cjk = extractText(pdf, page = 3)
        assertThat(cjk).contains("東京駅で会う")
        assertThat(cjk).contains("안녕하세요 서울")
        assertThat(cjk).contains("こんにちは")
    }

    @Test
    fun charactersOutsideTheBasicPlaneSurvive() {
        val pdf = service.writeBytes(spec(page(line("𠮷野家 😀 𝐀", 300f))))
        assertThat(extractText(pdf)).contains("𠮷野家 😀 𝐀")
    }

    @Test
    fun textIsStoredAsNfc() {
        val decomposed = "Café"
        val pdf = service.writeBytes(spec(page(line(decomposed, 300f))))
        assertThat(extractText(pdf)).contains("Café")
    }

    @Test
    fun invisibleLineSpansItsBoxWithinTwoPoints() {
        val pdf = service.writeBytes(spec(page(line("Invoice2026", top = 300f, left = 100f, right = 1100f))))
        val placed = glyphs(pdf)
        assertThat(placed).hasSize("Invoice2026".length)
        val scale = PageLayout.FIT_LONG_EDGE_PT / HEIGHT
        val first = placed.first()
        val last = placed.last()
        assertThat(first.xDirAdj).isWithin(2f).of(100f * scale)
        assertThat(last.xDirAdj + last.widthDirAdj).isWithin(2f).of(1100f * scale)
        assertThat(first.yDirAdj).isWithin(2f).of(360f * scale) // baseline = box bottom, measured from the top
        assertThat(first.fontSizeInPt).isWithin(2f).of(60f * scale)
    }

    @Test
    fun alignmentHoldsWithAMarginAndAPaperSize() {
        val spec = spec(page(line("Margins", top = 300f, left = 100f, right = 700f)), pageSize = PageSize.A4)
            .copy(margin = Margin.NORMAL)
        val pdf = service.writeBytes(spec)
        val placement = PageLayout.place(PageSize.A4, Margin.NORMAL, WIDTH, HEIGHT)
        val scale = placement.imageWidth / WIDTH
        val placed = glyphs(pdf)
        assertThat(placed.first().xDirAdj).isWithin(2f).of(placement.imageX + 100f * scale)
        assertThat(placed.last().xDirAdj + placed.last().widthDirAdj).isWithin(2f).of(placement.imageX + 700f * scale)
        val topOfImage = placement.pageHeight - placement.imageY - placement.imageHeight
        assertThat(placed.first().yDirAdj).isWithin(2f).of(topOfImage + 360f * scale)
    }

    @Test
    fun rotatedLineIsOneRunAlongItsAngle() {
        val pdf = service.writeBytes(spec(page(TextLine("Tilted line", BoxPx(100f, 300f, 1100f, 520f), angleDeg = 12f))))
        val placed = glyphs(pdf)
        assertThat(placed.joinToString("") { it.unicode }).isEqualTo("Tilted line")
        // Clockwise on the scan: each glyph sits lower on the page (larger y from the top) than the one before.
        assertThat(placed.zipWithNext().all { (a, b) -> b.y > a.y && b.x > a.x }).isTrue()
    }

    @Test
    fun gentlyTiltedLineFallsByLessThanPopplersLineHeight() {
        // 2.5° over 1000 px: the printed baseline falls 44 px, more than the 40 px line is high. Poppler would cut the
        // text where the fall passes half the font size ("Tokyo Stat" / "ion"); the text layer falls 0.4 × size.
        val text = "The quick brown fox jumps over the lazy dog"
        val pdf = service.writeBytes(spec(page(TextLine(text, rotatedBox(100f, 400f, 1000f, 40f, 2.5f), angleDeg = 2.5f))))
        val placed = glyphs(pdf)
        assertThat(placed.joinToString("") { it.unicode }).isEqualTo(text)
        val baselines = placed.map { it.textMatrix.translateY }
        val size = 40f * PageLayout.FIT_LONG_EDGE_PT / HEIGHT
        assertThat(baselines.max() - baselines.min()).isWithin(0.1f * size).of(TextLayer.MAX_RISE * size)
        assertThat(baselines.zipWithNext().all { (a, b) -> b < a }).isTrue() // still falls, as the print does
        assertThat(extractText(pdf)).contains(text)
    }

    @Test
    fun linesArriveInThePagesPixelSpaceNotTheJpegs() {
        // OCR ran on a 2480 px render; the exported JPEG is half that size. The writer scales the lines.
        val pdf = service.writeBytes(spec(page(line("Scaled", top = 600f, left = 200f, right = 2200f), w = 2 * WIDTH, h = 2 * HEIGHT)))
        val placed = glyphs(pdf)
        val scale = PageLayout.FIT_LONG_EDGE_PT / (2 * HEIGHT)
        assertThat(placed.first().xDirAdj).isWithin(2f).of(200f * scale)
        assertThat(placed.last().xDirAdj + placed.last().widthDirAdj).isWithin(2f).of(2200f * scale)
    }

    @Test
    fun jpegIsEmbeddedByteForByte() {
        val pdf = service.writeBytes(spec(page()))
        PDDocument.load(pdf).use { doc ->
            val resources = doc.getPage(0).resources
            val xobject = resources.xObjectNames.map { resources.getXObject(it) }.single() as PDImageXObject
            assertThat(xobject.cosObject.getItem(COSName.FILTER)).isEqualTo(COSName.DCT_DECODE)
            val raw = xobject.cosObject.createRawInputStream().use { it.readBytes() }
            assertThat(raw).isEqualTo(image.readBytes())
            assertThat(xobject.width).isEqualTo(WIDTH)
            assertThat(xobject.colorSpace.name).isEqualTo("DeviceRGB")
        }
    }

    @Test
    fun grayscaleJpegKeepsOneComponent() {
        val gray = TestImages.jpeg(tmp.newFile("gray.jpg"), 300, 400, gray = true)
        val pdf = service.writeBytes(spec(page(file = gray, w = 300, h = 400)))
        PDDocument.load(pdf).use { doc ->
            val resources = doc.getPage(0).resources
            val xobject = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
            assertThat(xobject.colorSpace.name).isEqualTo("DeviceGray")
        }
    }

    @Test
    fun pageSizesFollowTheSpec() {
        val wide = TestImages.jpeg(tmp.newFile("wide.jpg"), 1600, 900)
        val pdf = service.writeBytes(
            spec(page(), page(file = wide, w = 1600, h = 900), pageSize = PageSize.LETTER),
        )
        PDDocument.load(pdf).use { doc ->
            assertThat(doc.getPage(0).mediaBox.width).isEqualTo(612f)
            assertThat(doc.getPage(0).mediaBox.height).isEqualTo(792f)
            assertThat(doc.getPage(1).mediaBox.width).isEqualTo(792f)
            assertThat(doc.getPage(1).mediaBox.height).isEqualTo(612f)
        }
        val fit = service.writeBytes(spec(page(file = wide, w = 1600, h = 900)))
        PDDocument.load(fit).use { doc ->
            assertThat(doc.getPage(0).mediaBox.width).isWithin(0.01f).of(841.89f)
            assertThat(doc.getPage(0).mediaBox.height).isWithin(0.01f).of(841.89f * 900 / 1600)
        }
    }

    @Test
    fun metadataHeaderAndStats() {
        val pages = mutableListOf<Pair<Int, Int>>()
        val out = ByteArrayOutputStream()
        val stats = runBlocking {
            service.write(spec(page(line("One", 300f), line("Two", 400f)), page()), out) { done, total -> pages += done to total }
        }
        val pdf = out.toByteArray()
        assertThat(stats).isEqualTo(PdfStats(pages = 2, bytes = pdf.size.toLong(), textLines = 2))
        assertThat(pages).containsExactly(1 to 2, 2 to 2).inOrder()
        assertThat(String(pdf, 0, 8, Charsets.US_ASCII)).isEqualTo("%PDF-1.7")
        PDDocument.load(pdf).use { doc ->
            val info = doc.documentInformation
            assertThat(info.title).isEqualTo("Rent receipt")
            assertThat(info.producer).startsWith("heycam")
            assertThat(info.author).isNull()
            assertThat(info.creationDate.timeInMillis).isEqualTo(FIXED_NOW.toEpochMilli())
            assertThat(doc.documentCatalog.viewerPreferences.displayDocTitle()).isTrue()
        }
    }

    @Test
    fun withoutATextLayerThereIsNoFontAndNoText() {
        val pdf = service.writeBytes(spec(page(line("Hidden", 300f)), textLayer = false))
        assertThat(extractText(pdf).trim()).isEmpty()
        PDDocument.load(pdf).use { doc -> assertThat(doc.getPage(0).resources.fontNames.toList()).isEmpty() }
    }

    @Test
    fun theTextLayerFontIsEmbeddedOncePerDocument() {
        val pdf = service.writeBytes(spec(page(line("A", 300f)), page(line("B", 300f))))
        PDDocument.load(pdf).use { doc ->
            val f1 = doc.getPage(0).resources.getFont(COSName.getPDFName("F1"))
            val f2 = doc.getPage(1).resources.getFont(COSName.getPDFName("F1"))
            assertThat(f1.cosObject).isSameInstanceAs(f2.cosObject)
            assertThat(f1.isEmbedded).isTrue()
            assertThat(f1.name).isEqualTo(GlyphlessFont.NAME)
            // Apple's CoreGraphics (Preview, PDFKit) drops the whole text layer when Descent is 0.
            assertThat(f1.fontDescriptor.descent).isLessThan(0f)
        }
    }

    @Test
    fun aPasswordEncryptsWithAes256AndKeepsTheTextLayer() {
        val pdf = service.writeBytes(spec(page(line("Secret 42", 300f))).copy(password = Password("s3cret".toCharArray())))
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(pdf).close() }
        PDDocument.load(pdf, "s3cret").use { doc ->
            assertThat(doc.isEncrypted).isTrue()
            assertThat(doc.encryption.length).isEqualTo(256)
            assertThat(doc.encryption.revision).isEqualTo(6)
        }
        assertThat(String(pdf, 0, 8, Charsets.US_ASCII)).isEqualTo("%PDF-1.7")
        assertThat(extractText(pdf, password = "s3cret")).contains("Secret 42")
    }

    @Test
    fun inspectProtectAndUnlockRoundTrip() = runBlocking<Unit> {
        val plain = tmp.newFile("plain.pdf").apply { writeBytes(service.writeBytes(spec(page(line("Open", 300f))))) }
        assertThat(service.inspect(plain)).isEqualTo(PdfInfo(pageCount = 1, encrypted = false, needsPassword = false))

        val locked = tmp.newFile("locked.pdf")
        locked.outputStream().use { service.protect(plain, Password("pw".toCharArray()), it) }
        assertThat(service.inspect(locked)).isEqualTo(PdfInfo(pageCount = 0, encrypted = true, needsPassword = true))
        assertThat(service.inspect(locked, Password("pw".toCharArray())).encrypted).isTrue()

        val unlocked = tmp.newFile("unlocked.pdf")
        unlocked.outputStream().use { service.unlock(locked, Password("pw".toCharArray()), it) }
        assertThat(service.inspect(unlocked).encrypted).isFalse()
        assertThat(extractText(unlocked.readBytes())).contains("Open")
    }

    @Test
    fun mergeAndSplit() = runBlocking<Unit> {
        val a = tmp.newFile("a.pdf").apply { writeBytes(service.writeBytes(spec(page(line("Alpha", 300f)), page(line("Beta", 300f))))) }
        val b = tmp.newFile("b.pdf").apply { writeBytes(service.writeBytes(spec(page(line("Gamma", 300f))))) }
        val merged = tmp.newFile("merged.pdf")
        merged.outputStream().use { service.merge(listOf(a, b), it) }
        PDDocument.load(merged).use { assertThat(it.numberOfPages).isEqualTo(3) }
        assertThat(extractText(merged.readBytes(), page = 3)).contains("Gamma")

        val parts = mutableMapOf<Int, ByteArrayOutputStream>()
        service.split(merged, listOf(0..0, 1..2)) { index -> ByteArrayOutputStream().also { parts[index] = it } }
        PDDocument.load(parts.getValue(0).toByteArray()).use { assertThat(it.numberOfPages).isEqualTo(1) }
        PDDocument.load(parts.getValue(1).toByteArray()).use { assertThat(it.numberOfPages).isEqualTo(2) }
        assertThat(extractText(parts.getValue(1).toByteArray())).contains("Beta")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.split(merged, listOf(2..3)) { OutputStream.nullOutputStream() } }
        }
    }

    @Test
    fun pageNumbersAreReadable() {
        val pdf = service.writeBytes(spec(page(), page()).copy(pageNumbers = true))
        assertThat(extractText(pdf, page = 2)).contains("2 / 2")
    }

    @Test
    fun watermarkIsDrawnOnEveryPageFromOneImage() {
        val pdf = service.writeBytes(spec(page(), page()).copy(watermark = "COPY"))
        PDDocument.load(pdf).use { doc ->
            val images = (0 until 2).map { i ->
                val res = doc.getPage(i).resources
                res.xObjectNames.map { res.getXObject(it).cosObject }.toSet()
            }
            assertThat(images[0]).hasSize(2)
            assertThat(images[0].intersect(images[1])).hasSize(1) // the watermark is shared, page images are not
        }
    }

    @Test
    fun rejectsPagesThatAreNotJpeg() {
        val png = TestImages.png(tmp.newFile("p.png"), 10, 10)
        assertThrows(IllegalArgumentException::class.java) {
            service.writeBytes(spec(PdfPage(png, "image/png", 10, 10, emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) { service.writeBytes(spec()) }
    }

    private companion object {
        const val WIDTH = 1240
        const val HEIGHT = 1754
    }
}
