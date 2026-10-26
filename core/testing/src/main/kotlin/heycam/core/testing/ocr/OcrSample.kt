package heycam.core.testing.ocr

import heycam.core.common.StagingArea
import heycam.core.model.BoxPx
import heycam.core.model.PageOrigin
import heycam.core.model.StagedImage
import heycam.core.model.TextLine
import java.io.File

/**
 * Sample pages with the lines printed on them, for OCR-shaped tests (PLAN 4.13). Boxes are CoreText's typographic
 * bounds of each line, measured when the pages were drawn (A4 at 150 dpi). The images are resources under
 * heycam/samples/: sample_en_hi.jpg comes with the data and imaging fakes, sample_cjk.jpg is drawn by
 * core/testing/fixtures/render_sample_cjk.swift.
 */
enum class OcrSample(val fileName: String, val widthPx: Int, val heightPx: Int, val lines: List<TextLine>) {
    /** The spike's invoice: English and Hindi. */
    EN_HI(
        "sample_en_hi.jpg", 1240, 1754,
        listOf(
            line("HEYCAM SPIKE TEST INVOICE", 132, 911, 186),
            line("Invoice No. HC-2026-104", 238, 516, 274),
            line("Edgr Labs, 12 MG Road, Bengaluru 560001", 308, 812, 344),
            line("Phone: +91 98765 43210", 408, 515, 444),
            line("Email: billing@example.com", 478, 563, 514),
            line("Due date: 21 November 2026", 548, 582, 584),
            line("Scanned pages stay on this phone.", 664, 657, 709),
            line("Total amount due: Rs 4,250.00", 769, 683, 809),
            line("नमस्ते, यह एक परीक्षण दस्तावेज़ है।", 878, 732, 945),
            line("धन्यवाद", 978, 247, 1045),
        ),
    ),

    /** Japanese, Korean and Chinese lines with an English one. */
    CJK(
        "sample_cjk.jpg", 1240, 1754,
        listOf(
            line("HEYCAM CJK SAMPLE", 132, 707, 186),
            line("東京駅で会う", 268, 494, 332),
            line("明日の午後三時です", 378, 686, 442),
            line("서울역에서 만나요", 506, 570, 583),
            line("감사합니다", 616, 387, 693),
            line("北京欢迎你", 736, 430, 826),
            line("Meeting at Tokyo Station, 3 pm", 879, 658, 919),
        ),
    ),
    ;

    /** Copies the image to [target] and returns it. */
    fun copyTo(target: File): File {
        target.parentFile?.mkdirs()
        val stream = requireNotNull(OcrSample::class.java.classLoader?.getResourceAsStream("heycam/samples/$fileName")) {
            "missing sample $fileName"
        }
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** A fresh staged copy, as the scanner or an import would leave it. */
    fun stage(staging: StagingArea, origin: PageOrigin = PageOrigin.SCANNER): StagedImage =
        StagedImage(copyTo(staging.newStagedFile("jpg")), widthPx, heightPx, "image/jpeg", origin)
}

private fun line(text: String, top: Int, right: Int, bottom: Int) =
    TextLine(text, BoxPx(110f, top.toFloat(), right.toFloat(), bottom.toFloat()), angleDeg = 0f, confidence = 0.95f)
