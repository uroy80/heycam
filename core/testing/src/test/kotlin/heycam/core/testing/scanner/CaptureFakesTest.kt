package heycam.core.testing.scanner

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import heycam.core.common.StagingArea
import heycam.core.domain.CommitResult
import heycam.core.model.DocumentId
import heycam.core.model.ImageInfo
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedPage
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.ocr.OcrOutcome
import heycam.core.pdf.PdfPage
import heycam.core.pdf.PdfSpec
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScanStart
import heycam.core.testing.domain.FakeScanImporter
import heycam.core.testing.ocr.FakeOcrEngine
import heycam.core.testing.ocr.OcrSample
import heycam.core.testing.pdf.RecordingPdfService
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The capture and text fakes behave like the real seams they stand in for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaptureFakesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val staging = StagingArea { ext -> File.createTempFile("staged", ".$ext", tmp.root) }

    @Test
    fun scannerStagesFreshSampleCopiesUpToThePageLimit() = runBlocking {
        val scanner = FakeScannerLauncher(staging, pages = listOf(OcrSample.CJK, OcrSample.CJK))
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val start = scanner.start(activity, ScanRequest(pageLimit = 1))
        val pages = ((start as ScanStart.Immediate).result as ScanResult.Pages).images
        assertThat(pages).hasSize(1)
        assertThat(pages.single().file.length()).isGreaterThan(0L)
        assertThat(pages.single().origin).isEqualTo(PageOrigin.SCANNER)
        assertThat(scanner.requests).containsExactly(ScanRequest(pageLimit = 1))

        scanner.nextResult = ScanResult.Cancelled
        assertThat(scanner.start(activity, ScanRequest())).isEqualTo(ScanStart.Immediate(ScanResult.Cancelled))
    }

    @Test
    fun scannerInstallsBeforeItStarts() = runBlocking {
        val scanner = FakeScannerLauncher(staging)
        scanner.state = ModuleState.Downloading(0, null)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertThat(scanner.start(activity, ScanRequest())).isInstanceOf(ScanStart.Unavailable::class.java)
        assertThat(scanner.install().toList().last()).isEqualTo(ModuleState.Ready)
        assertThat(scanner.availability()).isEqualTo(ModuleState.Ready)
    }

    @Test
    fun ocrReadsOnlyWhatTheScriptsModelCan() = runBlocking {
        val ocr = FakeOcrEngine(OcrSample.EN_HI)
        val image = ImageInfo("/page.jpg", 620, 877, 1, "image/jpeg")
        val latin = ocr.recognize(image, OcrScript.LATIN) as OcrOutcome.Recognized
        assertThat(latin.text).contains("Invoice No. HC-2026-104")
        assertThat(latin.text).doesNotContain("धन्यवाद")
        assertThat(latin.lines.first().box.left).isEqualTo(55f) // scaled to the half-size image
        val devanagari = ocr.recognize(image, OcrScript.DEVANAGARI) as OcrOutcome.Recognized
        assertThat(devanagari.text).contains("धन्यवाद")
        assertThat(devanagari.text).contains("Invoice No. HC-2026-104")
    }

    @Test
    fun ocrModelsDownloadOnRequest() = runBlocking {
        val ocr = FakeOcrEngine(OcrSample.CJK)
        ocr.installed -= OcrScript.KOREAN
        val image = ImageInfo("/p.jpg", 1240, 1754, 1, "image/jpeg")
        assertThat(ocr.recognize(image, OcrScript.KOREAN)).isInstanceOf(OcrOutcome.ModelNotReady::class.java)
        assertThat(ocr.ensureModels(setOf(OcrScript.KOREAN)).toList().last()).isEqualTo(ModuleState.Ready)
        val korean = ocr.recognize(image, OcrScript.KOREAN) as OcrOutcome.Recognized
        assertThat(korean.text).contains("서울역에서 만나요")
        assertThat(korean.text).doesNotContain("東京駅で会う")
    }

    @Test
    fun recordingPdfKeepsThePageCountAcrossCalls() = runBlocking {
        val pdf = RecordingPdfService()
        val page = PdfPage(File("x.jpg"), "image/jpeg", 10, 10, OcrSample.EN_HI.lines)
        val file = tmp.newFile("a.pdf")
        val stats = file.outputStream().use { pdf.write(PdfSpec("A", listOf(page, page), PageSize.FIT, Margin.NONE, textLayer = true), it) }
        assertThat(stats.pages).isEqualTo(2)
        assertThat(stats.textLines).isEqualTo(2 * OcrSample.EN_HI.lines.size)
        assertThat(pdf.inspect(file).pageCount).isEqualTo(2)
        assertThat(pdf.written.single().title).isEqualTo("A")
    }

    @Test
    fun importerStagesAndCommits() = runBlocking {
        val importer = FakeScanImporter(staging, OcrSample.CJK)
        val uris = listOf(android.net.Uri.parse("content://a/1"), android.net.Uri.parse("content://a/2"))
        importer.rejected += uris[1]
        val staged = importer.stage(uris, PageOrigin.SHARE)
        assertThat(staged.images).hasSize(1)
        assertThat(staged.rejected).isEqualTo(1)
        val session = ScanSession("s", ImportTarget.AppendTo(DocumentId(7)), staged.images.map { StagedPage(it) }, Instant.EPOCH)
        val result = importer.commit(session)
        assertThat(result.documentId).isEqualTo(DocumentId(7))
        assertThat(importer.commit(session.copy(target = ImportTarget.Replace(PageId(3)))))
            .isEqualTo(CommitResult(DocumentId(1), listOf(PageId(3))))
    }
}
