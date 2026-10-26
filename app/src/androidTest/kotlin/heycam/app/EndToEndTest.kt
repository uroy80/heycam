package heycam.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey
import androidx.test.espresso.intent.matcher.IntentMatchers.hasFlag
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.domain.PageProcessor
import heycam.core.domain.ScanImporter
import heycam.core.domain.TextModels
import heycam.core.export.ExportRequest
import heycam.core.export.ExportResult
import heycam.core.export.ExportService
import heycam.core.export.ExportTarget
import heycam.core.export.TextPendingPolicy
import heycam.core.model.DocumentId
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.core.model.Settings
import heycam.core.model.TextStatus
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * PLAN §6 level 4, end to end on the real implementations: images in → Review with "filter all" → save → background
 * render and text (ML Kit, Devanagari model, which also reads English) → search in both scripts → searchable PDF and
 * TXT through the share path → chooser → trash → restore.
 *
 * The images arrive the way shared images do (a content:// URI through ScanImporter.stage), because ML Kit's scanner
 * is Google's own UI and cannot be driven here; everything after the scanner is the same path.
 */
@HiltAndroidTest
class EndToEndTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var textModels: TextModels
    @Inject lateinit var fileStore: FileStore
    @Inject lateinit var importer: ScanImporter
    @Inject lateinit var sessions: ScanSessionStore
    @Inject lateinit var processor: PageProcessor
    @Inject lateinit var documents: DocumentRepository
    @Inject lateinit var exports: ExportService

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var settingsBefore: Settings
    private var created: DocumentId? = null
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        hilt.inject()
        settingsBefore = settings.settings.first()
        settings.update { it.copy(ocrScript = OcrScript.DEVANAGARI, autoText = true) }
    }

    @After
    fun tearDown() = runBlocking {
        created?.let { documents.deleteForever(listOf(it)) }
        scratch.forEach { it.deleteRecursively() }
        settings.update { settingsBefore }
    }

    @Test
    fun scanReadSearchExportShareTrashRestore() = runBlocking {
        // One-time model download (Play services); the emulator is online for device tests.
        val models = withTimeout(MODEL_TIMEOUT_MS) { textModels.download(setOf(OcrScript.DEVANAGARI)).last() }
        assertWithMessage("Devanagari text model").that(models).isEqualTo(ModuleState.Ready)

        // Images in, as a share would bring them.
        val staged = importer.stage(listOf(sampleUri()), PageOrigin.SHARE)
        assertThat(staged.rejected).isEqualTo(0)
        assertThat(staged.images).hasSize(1)

        // Review: "Use Grey on all pages", persisted so it would survive process death.
        val session = sessions.create(ImportTarget.NewDocument(), staged.images)
        sessions.update(session.copy(pages = session.pages.map { it.copy(edits = it.edits.copy(filter = PageFilter.GRAYSCALE)) }))
        val reviewed = sessions.get(session.id)!!
        assertThat(reviewed.pages.map { it.edits.filter }).containsExactly(PageFilter.GRAYSCALE)

        // Save, then the background pipeline renders and reads the page.
        val id = importer.commit(reviewed).documentId
        created = id
        withTimeout(PIPELINE_TIMEOUT_MS) { processor.awaitIdle(listOf(id)) }
        val document = documents.observeDocument(id).first()!!
        assertThat(document.pages).hasSize(1)
        val page = document.pages.single()
        assertThat(page.edits.filter).isEqualTo(PageFilter.GRAYSCALE)
        assertThat(page.render).isNotNull()
        assertThat(page.textStatus).isEqualTo(TextStatus.DONE)

        // Search inside the recognised text, Latin and Devanagari.
        listOf(LATIN_WORD, DEVANAGARI_WORD).forEach { word ->
            assertWithMessage(word).that(textHits(word)).contains(id)
        }

        // Searchable PDF through the share path: a private content URI with a one-off read grant.
        val pdfShare = share(id, ExportOptions(format = ExportFormat.PDF, searchable = true))
        val pdfUri = streamOf(pdfShare).single()
        assertThat(pdfUri.authority).isEqualTo("${context.packageName}.exports")
        val pdf = copyToScratch(pdfUri, "e2e.pdf")
        assertThat(pdf.readBytes().copyOfRange(0, 8).decodeToString()).isEqualTo("%PDF-1.7")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val text = pdfText(pdf)
            assertWithMessage("PDF text layer").that(text).contains(LATIN_WORD)
            assertWithMessage("PDF text layer").that(text).contains(DEVANAGARI_WORD)
        }

        // Text file through the same path.
        val txt = copyToScratch(streamOf(share(id, ExportOptions(format = ExportFormat.TEXT))).single(), "e2e.txt")
        assertThat(txt.readText()).contains(LATIN_WORD)

        // The chooser opens from the app with its grant (Espresso-Intents answers for the system UI).
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startActivity(pdfShare) }
            }
            intended(
                allOf(
                    hasAction(Intent.ACTION_CHOOSER),
                    hasExtraWithKey(Intent.EXTRA_INTENT),
                    hasFlag(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                ),
            )
        } finally {
            Intents.release()
        }

        // Trash hides it from search; restore brings it back.
        documents.trash(listOf(id))
        assertThat(documents.observeTrash().first().map { it.id }).contains(id)
        assertThat(textHits(LATIN_WORD)).doesNotContain(id)
        documents.restore(listOf(id))
        assertThat(documents.observeTrash().first().map { it.id }).doesNotContain(id)
        assertThat(textHits(LATIN_WORD)).contains(id)
    }

    /** The test APK's sample page, copied under the exports provider so the importer reads it as content://. */
    private fun sampleUri(): Uri {
        val dir = File(fileStore.exportDir(), "e2e-${UUID.randomUUID()}").apply { mkdirs() }
        scratch += dir
        val file = File(dir, "sample_en_hi.jpg")
        testAssets.open("sample_en_hi.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    private suspend fun textHits(word: String): List<DocumentId> =
        documents.search(SearchQuery(word)).filterIsInstance<SearchHit.TextHit>().map { it.document.id }

    private suspend fun share(id: DocumentId, options: ExportOptions): Intent {
        val request = ExportRequest(listOf(id), options, whenTextPending = TextPendingPolicy.WAIT)
        val result = withTimeout(EXPORT_TIMEOUT_MS) { exports.export(request, ExportTarget.Share) }
        assertWithMessage("export ${options.format}").that(result).isInstanceOf(ExportResult.ReadyToShare::class.java)
        val chooser = (result as ExportResult.ReadyToShare).chooser
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        return chooser
    }

    private fun streamOf(chooser: Intent): List<Uri> {
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        return when (send.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> error("unexpected share action ${send.action}")
        }
    }

    private fun copyToScratch(uri: Uri, name: String): File {
        val file = File(context.cacheDir, "e2e-${UUID.randomUUID()}-$name")
        scratch += file
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** The framework's own PDF engine (API 35+), as a viewer would read the invisible text layer. */
    private fun pdfText(pdf: File): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        return android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until renderer.pageCount).joinToString("\n") { index ->
                    renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
                }
            }
        }
    }

    private companion object {
        const val LATIN_WORD = "Invoice"
        const val DEVANAGARI_WORD = "धन्यवाद"
        const val MODEL_TIMEOUT_MS = 300_000L
        const val PIPELINE_TIMEOUT_MS = 120_000L
        const val EXPORT_TIMEOUT_MS = 60_000L
    }
}
