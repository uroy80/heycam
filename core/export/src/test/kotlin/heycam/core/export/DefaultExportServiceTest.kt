package heycam.core.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import heycam.core.model.BoxPx
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.PageText
import heycam.core.model.Rotation
import heycam.core.model.SizePreset
import heycam.core.model.TextLine
import heycam.core.model.TextStatus
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultExportServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val library = FakeLibrary()
    private val renderer = ImageIoRenderer()
    private lateinit var service: DefaultExportService
    private val doc = DocumentId(1)

    @Before
    fun setUp() {
        // FileProvider caches each authority's roots for the life of the process; Robolectric gives every test a
        // fresh cache directory, so the cache from an earlier test would point at the wrong place.
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        PDFBoxResourceLoader.init(context)
        service = DefaultExportService(context, library, FakeFiles(context), renderer, pdfService(context, tmp.newFolder("s")), InPlace, SilentLog)
    }

    private fun page(id: Long, index: Int, edits: PageEdits = PageEdits(), status: TextStatus = TextStatus.DONE, w: Int = 1240, h: Int = 1754): Page {
        val source = TestImages.jpeg(tmp.newFile("source-$id.jpg"), w, h)
        val render = if (edits.isIdentity) source else {
            val turned = edits.rotation == Rotation.R90 || edits.rotation == Rotation.R270
            TestImages.jpeg(tmp.newFile("render-$id.jpg"), if (turned) h else w, if (turned) w else h)
        }
        return Page(PageId(id), doc, index, "key$id", source, edits, 0, render, null, PageOrigin.SCANNER, status, OcrScript.LATIN)
    }

    private fun document(vararg pages: Page, title: String = "Rent receipt", id: DocumentId = doc, trashed: Boolean = false) {
        library.put(Document(id, title, null, T0, T0, false, if (trashed) T0 else null, null, pages.map { it.copy(documentId = id) }))
    }

    private fun text(page: Page, vararg lines: String) = PageText(
        pageId = page.id,
        script = OcrScript.LATIN,
        text = lines.joinToString("\n"),
        lines = lines.mapIndexed { i, line -> TextLine(line, BoxPx(100f, 200f + 80f * i, 100f + 30f * line.length, 250f + 80f * i)) },
        imageWidthPx = page.render!!.widthPx,
        imageHeightPx = page.render!!.heightPx,
        editsVersion = 0,
        engine = "test",
        recognizedAt = T0,
    )

    private fun export(options: ExportOptions, target: ExportTarget = ExportTarget.Share, policy: TextPendingPolicy = TextPendingPolicy.ASK, ids: List<DocumentId> = listOf(doc)) =
        runBlocking { service.export(ExportRequest(ids, options, policy), target) }

    /** The files a share result points at, through the FileProvider. */
    private fun shared(result: ExportResult): List<File> {
        assertThat(result).isInstanceOf(ExportResult.ReadyToShare::class.java)
        val chooser = (result as ExportResult.ReadyToShare).chooser
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        val uris = if (send.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION") listOf(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!)
        } else {
            @Suppress("DEPRECATION") send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        }
        val clip: ClipData = send.clipData!!
        assertThat((0 until clip.itemCount).map { clip.getItemAt(it).uri }).isEqualTo(uris)
        uris.forEach { assertThat(it.authority).isEqualTo("${context.packageName}.exports") }
        return uris.map { uri -> File(File(context.cacheDir, "exports"), uri.path!!.removePrefix("/exports/")) }
    }

    private fun pdfText(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    /** The service on the test scheduler, so waiting for text runs on virtual time. */
    private fun TestScope.timedService() = DefaultExportService(
        context, library, FakeFiles(context), renderer, pdfService(context, tmp.newFolder("timed")),
        OnDispatcher(UnconfinedTestDispatcher(testScheduler)), SilentLog,
    )

    /** A folder behind a documents provider, like the one the system "Save to" picker writes into. */
    private fun pickerFolder(): File {
        val folder = tmp.newFolder("Drive")
        val provider = Robolectric.buildContentProvider(DirectoryDocumentsProvider::class.java).create(
            ProviderInfo().apply {
                authority = DirectoryDocumentsProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            },
        ).get()
        provider.root = folder
        return folder
    }

    /** What ACTION_CREATE_DOCUMENT hands back: a new, empty file in [folder]. */
    private fun picked(folder: File, name: String): Uri {
        File(folder, name).createNewFile()
        return DocumentsContract.buildDocumentUri(DirectoryDocumentsProvider.AUTHORITY, name)
    }

    private fun markDone(page: Page) =
        library.update(doc) { d -> d.copy(pages = d.pages.map { if (it.id == page.id) it.copy(textStatus = TextStatus.DONE) else it }) }

    @Test
    fun searchablePdfSharesOneFileWithTheTextLayer() {
        val plain = page(1, 0)
        val turned = page(2, 1, PageEdits(rotation = Rotation.R90))
        document(plain, turned)
        library.setText(doc, listOf(text(plain, "Invoice 2026-10"), text(turned, "धन्यवाद")))

        val files = shared(export(ExportOptions(format = ExportFormat.PDF)))

        assertThat(files.map { it.name }).containsExactly("Rent receipt.pdf")
        PDDocument.load(files.single()).use { pdf ->
            assertThat(pdf.numberOfPages).isEqualTo(2)
            assertThat(pdf.documentInformation.title).isEqualTo("Rent receipt")
            assertThat(pdf.getPage(1).mediaBox.width).isGreaterThan(pdf.getPage(1).mediaBox.height) // the turned page
        }
        val text = pdfText(files.single())
        assertThat(text).contains("Invoice 2026-10")
        assertThat(text).contains("धन्यवाद")
        assertThat(renderer.calls.map { it.second.maxLongEdgePx }.toSet()).containsExactly(SizePreset.STANDARD.maxLongEdgePx)
        assertThat(renderer.calls.map { it.first }.toSet()).containsExactly(PageEdits()) // renders are re-sized, not re-edited
    }

    @Test
    fun notSearchableLeavesTheTextOut() {
        val p = page(1, 0)
        document(p)
        library.setText(doc, listOf(text(p, "Secret")))
        val file = shared(export(ExportOptions(searchable = false))).single()
        assertThat(pdfText(file).trim()).isEmpty()
    }

    @Test
    fun textReadForAnOlderGeometryIsNotPlaced() {
        val p = page(1, 0, status = TextStatus.PENDING)
        document(p)
        library.setText(doc, listOf(text(p, "Old words")))
        val file = shared(export(ExportOptions(), policy = TextPendingPolicy.WITHOUT_TEXT)).single()
        assertThat(pdfText(file)).doesNotContain("Old words")
    }

    @Test
    fun pendingTextIsAskedAboutFirst() {
        document(page(1, 0, status = TextStatus.PENDING), page(2, 1, status = TextStatus.PENDING), page(3, 2))
        assertThat(export(ExportOptions())).isEqualTo(ExportResult.TextPending(2))
        assertThat(export(ExportOptions(format = ExportFormat.TEXT))).isEqualTo(ExportResult.TextPending(2))
        // Images and non-searchable PDFs never wait for text.
        assertThat(export(ExportOptions(format = ExportFormat.JPEG))).isInstanceOf(ExportResult.ReadyToShare::class.java)
        assertThat(export(ExportOptions(searchable = false))).isInstanceOf(ExportResult.ReadyToShare::class.java)
    }

    @Test
    fun waitingExportsOnceTheTextIsRead() = runBlocking {
        val p = page(1, 0, status = TextStatus.PENDING)
        document(p)
        val result = async { service.export(ExportRequest(listOf(doc), ExportOptions(), TextPendingPolicy.WAIT), ExportTarget.Share) }
        yield()
        assertThat(result.isCompleted).isFalse()
        library.setText(doc, listOf(text(p, "Read at last")))
        library.update(doc) { d -> d.copy(pages = d.pages.map { it.copy(textStatus = TextStatus.DONE) }) }
        assertThat(pdfText(shared(result.await()).single())).contains("Read at last")
    }

    @Test
    fun waitingStopsOnceNoPageHasBeenReadForAWhile() = runTest {
        val service = timedService()
        val read = page(1, 0)
        val slow = page(2, 1, status = TextStatus.PENDING)
        val stuck = page(3, 2, status = TextStatus.PENDING) // its text model cannot download, say
        document(read, slow, stuck)
        library.setText(doc, listOf(text(read, "Read already"), text(slow, "Read later"), text(stuck, "Never read")))
        val result = async { service.export(ExportRequest(listOf(doc), ExportOptions(), TextPendingPolicy.WAIT), ExportTarget.Share) }

        advanceTimeBy(15_000)
        assertThat(result.isCompleted).isFalse()
        markDone(slow) // progress: the wait starts over
        advanceTimeBy(19_000)
        assertThat(result.isCompleted).isFalse()
        advanceTimeBy(2_000)

        assertThat(result.isCompleted).isTrue()
        val text = pdfText(shared(result.await()).single())
        assertThat(text).contains("Read already")
        assertThat(text).contains("Read later")
        assertThat(text).doesNotContain("Never read") // still PENDING: no text layer for that page
    }

    @Test
    fun leavingAWaitingSaveRemovesThePickedFile() = runTest {
        val service = timedService()
        val folder = pickerFolder()
        document(page(1, 0, status = TextStatus.PENDING))
        val uri = picked(folder, "Rent receipt.pdf")
        val saving = launch {
            service.export(ExportRequest(listOf(doc), ExportOptions(), TextPendingPolicy.WAIT), ExportTarget.Document(uri))
        }
        advanceTimeBy(5_000)
        assertThat(saving.isActive).isTrue()

        saving.cancelAndJoin() // the sheet was closed

        assertThat(folder.list()!!.toList()).isEmpty()
    }

    @Test
    fun aWaitingSaveWritesThePickedFileOnceTheTextIsRead() = runTest {
        val service = timedService()
        val folder = pickerFolder()
        val p = page(1, 0, status = TextStatus.PENDING)
        document(p)
        library.setText(doc, listOf(text(p, "Read at last")))
        val uri = picked(folder, "Rent receipt.pdf")
        val result = async {
            service.export(ExportRequest(listOf(doc), ExportOptions(), TextPendingPolicy.WAIT), ExportTarget.Document(uri))
        }
        advanceTimeBy(3_000)
        markDone(p)
        assertThat(result.await()).isEqualTo(ExportResult.Saved(listOf(uri)))
        assertThat(pdfText(File(folder, "Rent receipt.pdf"))).contains("Read at last")
    }

    @Test
    fun jpegExportIsOneFilePerPageAtThePresetSize() {
        document(page(1, 0), page(2, 1, PageEdits(filter = PageFilter.GRAYSCALE)))
        val files = shared(export(ExportOptions(format = ExportFormat.JPEG, size = SizePreset.SMALL)))
        assertThat(files.map { it.name }).containsExactly("Rent receipt - p01.jpg", "Rent receipt - p02.jpg").inOrder()
        files.forEach { file ->
            val image = ImageIO.read(file)
            assertThat(max(image.width, image.height)).isEqualTo(minOf(1754, SizePreset.SMALL.maxLongEdgePx!!))
        }
        assertThat(renderer.calls.map { it.second.quality }.toSet()).containsExactly(SizePreset.SMALL.jpegQuality)
    }

    @Test
    fun originalJpegPassesTheBytesThrough() {
        val p = page(1, 0)
        document(p)
        val file = shared(export(ExportOptions(format = ExportFormat.JPEG, size = SizePreset.ORIGINAL))).single()
        assertThat(file.readBytes()).isEqualTo(File(p.source.path).readBytes())
        assertThat(renderer.calls).isEmpty()
    }

    @Test
    fun pngIsReallyPng() {
        document(page(1, 0, w = 300, h = 400))
        val file = shared(export(ExportOptions(format = ExportFormat.PNG, size = SizePreset.ORIGINAL))).single()
        assertThat(file.name).isEqualTo("Rent receipt - p01.png")
        assertThat(file.readBytes().copyOf(4)).isEqualTo(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
    }

    @Test
    fun textExportHeadsEachPageWhenThereAreSeveral() {
        val a = page(1, 0)
        val b = page(2, 1)
        val c = page(3, 2, status = TextStatus.EMPTY)
        document(a, b, c)
        library.setText(doc, listOf(text(a, "First line", "Second line"), text(b, "नमस्ते")))
        val file = shared(export(ExportOptions(format = ExportFormat.TEXT))).single()
        assertThat(file.name).isEqualTo("Rent receipt.txt")
        assertThat(file.readText()).isEqualTo("Page 1\nFirst line\nSecond line\n\nPage 2\nनमस्ते\n\nPage 3\n")

        document(a)
        assertThat(shared(export(ExportOptions(format = ExportFormat.TEXT))).single().readText()).isEqualTo("First line\nSecond line\n")
    }

    @Test
    fun aChosenFileNameAndAPageSubsetAreHonoured() {
        val pages = arrayOf(page(1, 0), page(2, 1), page(3, 2))
        document(*pages)
        val options = ExportOptions(format = ExportFormat.JPEG, fileName = "Lease: page/2", pages = listOf(PageId(2)))
        assertThat(shared(export(options)).map { it.name }).containsExactly("Lease- page-2 - p01.jpg")
    }

    @Test
    fun severalDocumentsWithOneTitleGetDistinctNames() {
        document(page(1, 0))
        document(page(2, 0), id = DocumentId(2))
        val files = shared(export(ExportOptions(searchable = false), ids = listOf(doc, DocumentId(2))))
        assertThat(files.map { it.name }).containsExactly("Rent receipt.pdf", "Rent receipt (2).pdf")
    }

    @Test
    fun trashedOrMissingDocumentsAndFilesAreNotExported() {
        document(page(1, 0), trashed = true)
        assertThat(export(ExportOptions())).isEqualTo(ExportResult.Failed(ExportFailure.SOURCE_MISSING))
        assertThat(export(ExportOptions(), ids = listOf(DocumentId(9)))).isEqualTo(ExportResult.Failed(ExportFailure.SOURCE_MISSING))

        val p = page(5, 0)
        document(p)
        File(p.source.path).delete()
        assertThat(export(ExportOptions(searchable = false))).isEqualTo(ExportResult.Failed(ExportFailure.SOURCE_MISSING))
    }

    @Test
    fun aFullDiskIsReportedAsNoSpace() {
        document(page(1, 0))
        renderer.failWith = IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))
        assertThat(export(ExportOptions(searchable = false))).isEqualTo(ExportResult.Failed(ExportFailure.NO_SPACE))
        assertThat(File(context.cacheDir, "exports").listFiles()!!.toList()).isEmpty() // nothing half-written is left
    }

    @Test
    fun runningOutOfMemoryFailsTheExportAndLeavesNothingBehind() = runBlocking {
        val folder = pickerFolder()
        document(page(1, 0))
        renderer.failWith = OutOfMemoryError("64 MB")

        assertThat(export(ExportOptions(searchable = false))).isEqualTo(ExportResult.Failed(ExportFailure.WRITE_FAILED))
        assertThat(File(context.cacheDir, "exports").listFiles()!!.toList()).isEmpty()

        val uri = picked(folder, "Rent receipt.pdf")
        assertThat(export(ExportOptions(searchable = false), ExportTarget.Document(uri)))
            .isEqualTo(ExportResult.Failed(ExportFailure.WRITE_FAILED))
        assertThat(folder.list()!!.toList()).isEmpty()

        val request = ExportRequest(listOf(doc), ExportOptions(), TextPendingPolicy.WITHOUT_TEXT)
        val error = assertThrows(ExportException::class.java) { runBlocking { service.printablePdf(request) } }
        assertThat(error.failure).isEqualTo(ExportFailure.WRITE_FAILED)
        assertThat(service.estimate(request).measured).isFalse()
    }

    @Test
    fun aFailedSaveRemovesThePickedFile() {
        val folder = pickerFolder()
        document(page(1, 0), page(2, 1))
        val uri = picked(folder, "Rent receipt - p01.jpg")
        assertThat(export(ExportOptions(format = ExportFormat.JPEG), ExportTarget.Document(uri)))
            .isEqualTo(ExportResult.Failed(ExportFailure.WRITE_FAILED))
        assertThat(folder.list()!!.toList()).isEmpty()
    }

    @Test
    fun discardRemovesAPickedFileNothingWillFill() = runTest {
        val service = timedService()
        val folder = pickerFolder()
        val uri = picked(folder, "Rent receipt.pdf")
        service.discard(ExportTarget.Share) // nothing was picked: nothing to remove
        assertThat(folder.list()!!.toList()).containsExactly("Rent receipt.pdf")
        service.discard(ExportTarget.Document(uri))
        assertThat(folder.list()!!.toList()).isEmpty()
    }

    @Test
    fun savesIntoThePickedFile() {
        document(page(1, 0))
        val target = tmp.newFile("picked.pdf")
        val uri = Uri.fromFile(target)
        assertThat(export(ExportOptions(searchable = false), ExportTarget.Document(uri))).isEqualTo(ExportResult.Saved(listOf(uri)))
        PDDocument.load(target).use { assertThat(it.numberOfPages).isEqualTo(1) }
    }

    @Test
    fun aSinglePickedFileCannotTakeSeveralFiles() {
        document(page(1, 0), page(2, 1))
        val uri = Uri.fromFile(tmp.newFile("one.jpg"))
        assertThat(export(ExportOptions(format = ExportFormat.JPEG), ExportTarget.Document(uri)))
            .isEqualTo(ExportResult.Failed(ExportFailure.WRITE_FAILED))
    }

    @Test
    fun savesEveryFileIntoThePickedFolder() {
        val folder = pickerFolder()
        document(page(1, 0), page(2, 1))
        val tree = DocumentsContract.buildTreeDocumentUri(DirectoryDocumentsProvider.AUTHORITY, DirectoryDocumentsProvider.ROOT)

        val result = export(ExportOptions(format = ExportFormat.JPEG), ExportTarget.Folder(tree))

        assertThat(result).isInstanceOf(ExportResult.Saved::class.java)
        assertThat((result as ExportResult.Saved).uris).hasSize(2)
        assertThat(folder.list()!!.sorted()).containsExactly("Rent receipt - p01.jpg", "Rent receipt - p02.jpg").inOrder()
        folder.listFiles()!!.forEach { assertThat(ImageIO.read(it)).isNotNull() }
    }

    @Test
    fun estimatesCountTextAndPassedThroughBytesAndMeasureTheRest() = runBlocking {
        val a = page(1, 0)
        val b = page(2, 1)
        document(a, b)
        library.setText(doc, listOf(text(a, "Hello"), text(b, "World")))
        fun request(options: ExportOptions) = ExportRequest(listOf(doc), options, TextPendingPolicy.WITHOUT_TEXT)

        val text = service.estimate(request(ExportOptions(format = ExportFormat.TEXT)))
        assertThat(text).isEqualTo(SizeEstimate("Page 1\nHello\n\nPage 2\nWorld\n".toByteArray().size.toLong(), measured = true))

        val original = service.estimate(request(ExportOptions(format = ExportFormat.JPEG, size = SizePreset.ORIGINAL)))
        assertThat(original).isEqualTo(SizeEstimate(a.source.bytes + b.source.bytes, measured = true))

        val small = service.estimate(request(ExportOptions(format = ExportFormat.JPEG, size = SizePreset.SMALL)))
        assertThat(small.measured).isTrue()
        assertThat(small.bytes).isGreaterThan(0L)
        assertThat(small.bytes).isLessThan(original.bytes)
        assertThat(File(context.cacheDir, "exports").listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun estimatesAddUpPagesOfMixedSize() {
        // The shape of a lease scanned in parts: a full page, a phone photo, a half page and a small stamp.
        document(page(1, 0), page(2, 1, w = 480, h = 640), page(3, 2, w = 910, h = 1475), page(4, 3, w = 300, h = 200))
        fun options(format: ExportFormat, size: SizePreset) = ExportOptions(format = format, size = size, searchable = false)
        fun estimate(format: ExportFormat, size: SizePreset) =
            runBlocking { service.estimate(ExportRequest(listOf(doc), options(format, size), TextPendingPolicy.WITHOUT_TEXT)) }

        for (format in listOf(ExportFormat.JPEG, ExportFormat.PDF)) {
            val small = estimate(format, SizePreset.SMALL)
            val actual = shared(export(options(format, SizePreset.SMALL))).sumOf { it.length() }
            assertThat(small.measured).isTrue()
            assertThat(small.bytes.toDouble()).isWithin(0.35 * actual).of(actual.toDouble())
            assertThat(small.bytes).isAtMost(estimate(format, SizePreset.STANDARD).bytes)
            assertThat(small.bytes).isLessThan(estimate(format, SizePreset.ORIGINAL).bytes)
        }
    }

    @Test
    fun suggestedNamesFollowTheRules() {
        assertThat(service.suggestedFileName("Rent: Oct?", ExportOptions())).isEqualTo("Rent- Oct-.pdf")
        assertThat(service.suggestedFileName("x", ExportOptions(format = ExportFormat.JPEG, fileName = "Lease"), index = 2))
            .isEqualTo("Lease - p03.jpg")
        assertThat(service.suggestedFileName("   ", ExportOptions(format = ExportFormat.TEXT))).isEqualTo("heycam.txt")
    }

    @Test
    fun printablePdfHasEveryPageAndNoText() = runBlocking {
        val a = page(1, 0)
        document(a)
        document(page(2, 0), id = DocumentId(2), title = "Second")
        library.setText(doc, listOf(text(a, "Not for print")))
        val printable = service.printablePdf(ExportRequest(listOf(doc, DocumentId(2)), ExportOptions(), TextPendingPolicy.WITHOUT_TEXT))
        assertThat(printable.pageCount).isEqualTo(2)
        assertThat(printable.jobName).isEqualTo("heycam")
        assertThat(pdfText(printable.file).trim()).isEmpty()
        PDDocument.load(printable.file).use { assertThat(it.numberOfPages).isEqualTo(2) }
    }
}
