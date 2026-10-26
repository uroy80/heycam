package heycam.core.domain

import android.graphics.Bitmap
import android.net.Uri
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.data.NewPage
import heycam.core.data.PageWorkStore
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.imaging.ImageImporter
import heycam.core.imaging.ImportedImage
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RejectReason
import heycam.core.imaging.RenderSpec
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.FolderId
import heycam.core.model.ImageInfo
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageId
import heycam.core.model.PageOrigin
import heycam.core.model.PageText
import heycam.core.model.Rotation
import heycam.core.model.Settings
import heycam.core.model.TextStatus
import heycam.core.ocr.OcrEngine
import heycam.core.ocr.OcrOutcome
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An implementation of [T] whose members all throw. Fakes delegate to it (`by unused()`) and override only what a
 * test touches, so they keep compiling when an interface gains members the test does not need.
 */
inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name} is not used here")
    } as T

class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

object SilentLog : HeycamLog {
    override fun d(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, error: Throwable?) = Unit
    override fun e(tag: String, message: String, error: Throwable?) = Unit
}

val T0: Instant = Instant.parse("2026-10-20T09:02:00Z")

/**
 * Documents and pages in memory, behaving like :core:data's PageWorkStore contract: renders are current when their
 * editsVersion matches, text work is offered only for current renders, stale writes are refused.
 */
class InMemoryLibrary(private val root: File) : DocumentRepository by unused(), PageWorkStore, FileStore by unused() {
    private data class Doc(val title: String, val folderId: FolderId?, var scriptOverride: OcrScript? = null, var trashed: Boolean = false)

    private val docs = linkedMapOf<DocumentId, Doc>()
    private val pages = linkedMapOf<PageId, Page>()
    val texts = linkedMapOf<PageId, PageText>()
    private var nextId = 1L
    private val changes = MutableStateFlow(0)

    override fun pageDir(fileKey: String): File = File(root, "pages/$fileKey").apply { mkdirs() }

    fun page(id: PageId): Page = pages.getValue(id)

    fun pagesOf(id: DocumentId): List<Page> = pages.values.filter { it.documentId == id }.sortedBy { it.index }

    fun title(id: DocumentId): String = docs.getValue(id).title

    fun folder(id: DocumentId): FolderId? = docs.getValue(id).folderId

    fun scriptOverride(id: DocumentId): OcrScript? = docs.getValue(id).scriptOverride

    fun editPage(id: PageId, transform: (PageEdits) -> PageEdits) = update(id) { page ->
        val edits = transform(page.edits)
        page.copy(
            edits = edits,
            editsVersion = page.editsVersion + 1,
            render = null,
            textStatus = if (edits.sameGeometryAs(page.edits)) page.textStatus else TextStatus.PENDING,
        )
    }

    private fun update(id: PageId, transform: (Page) -> Page) {
        pages[id] = transform(pages.getValue(id))
        changes.update { it + 1 }
    }

    private fun newPage(documentId: DocumentId, index: Int, staged: NewPage, id: PageId = PageId(nextId++)): Page {
        val key = UUID.randomUUID().toString()
        val source = File(pageDir(key), "source.jpg")
        staged.image.file.copyTo(source, overwrite = true)
        staged.image.file.delete()
        return Page(
            id = id,
            documentId = documentId,
            index = index,
            fileKey = key,
            source = ImageInfo(source.path, staged.image.widthPx, staged.image.heightPx, source.length(), staged.image.mime),
            edits = staged.edits,
            editsVersion = 0,
            render = null,
            thumbPath = null,
            origin = staged.image.origin,
            textStatus = TextStatus.PENDING,
            textScript = null,
        )
    }

    // ---- DocumentRepository (the parts the domain uses) ----

    override fun observeDocument(id: DocumentId): Flow<Document?> = changes.map {
        docs[id]?.let { doc ->
            Document(id, doc.title, doc.folderId, T0, T0, false, if (doc.trashed) T0 else null, doc.scriptOverride, pagesOf(id))
        }
    }

    /** Runs once a document is saved, before createDocument returns: where Room's commit hands back to the caller. */
    var afterCreate: suspend () -> Unit = {}

    override suspend fun createDocument(title: String, folderId: FolderId?, pages: List<NewPage>): DocumentId {
        val id = DocumentId(nextId++)
        docs[id] = Doc(title, folderId)
        pages.forEachIndexed { i, staged -> newPage(id, i, staged).let { this.pages[it.id] = it } }
        changes.update { it + 1 }
        afterCreate()
        return id
    }

    override suspend fun addPages(documentId: DocumentId, pages: List<NewPage>, atIndex: Int?): List<PageId> {
        if (documentId !in docs) throw NoSuchElementException("document ${documentId.value}")
        val existing = pagesOf(documentId)
        val at = (atIndex ?: existing.size).coerceIn(0, existing.size)
        existing.filter { it.index >= at }.forEach { this.pages[it.id] = it.copy(index = it.index + pages.size) }
        return pages.mapIndexed { i, staged -> newPage(documentId, at + i, staged).also { this.pages[it.id] = it }.id }
            .also { changes.update { it + 1 } }
    }

    override suspend fun replacePage(pageId: PageId, page: NewPage) {
        val old = pages[pageId] ?: throw NoSuchElementException()
        pages[pageId] = newPage(old.documentId, old.index, page, id = pageId).copy(editsVersion = old.editsVersion + 1)
        texts.remove(pageId)
        changes.update { it + 1 }
    }

    override suspend fun setScriptOverride(id: DocumentId, script: OcrScript?) {
        docs.getValue(id).scriptOverride = script
        changes.update { it + 1 }
    }

    // ---- PageWorkStore ----

    private fun live(page: Page) = docs[page.documentId]?.trashed == false

    override suspend fun pagesNeedingRender(limit: Int): List<Page> =
        pages.values.filter { live(it) && it.render == null }.take(limit)

    override suspend fun pagesNeedingText(limit: Int): List<Page> =
        pages.values.filter { live(it) && it.render != null && it.textStatus == TextStatus.PENDING }.take(limit)

    override suspend fun saveRender(pageId: PageId, editsVersion: Int, render: ImageInfo, thumbPath: String): Boolean {
        val page = pages[pageId] ?: return false
        if (page.editsVersion != editsVersion) return false
        update(pageId) { it.copy(render = render, thumbPath = thumbPath) }
        return true
    }

    override suspend fun saveText(text: PageText): Boolean {
        val page = pages[text.pageId] ?: return false
        if (page.editsVersion != text.editsVersion) return false
        texts[text.pageId] = text
        update(text.pageId) {
            it.copy(textStatus = if (text.text.isBlank()) TextStatus.EMPTY else TextStatus.DONE, textScript = text.script)
        }
        return true
    }

    override suspend fun setTextStatus(pageId: PageId, editsVersion: Int, status: TextStatus): Boolean {
        val page = pages[pageId] ?: return false
        if (page.editsVersion != editsVersion) return false
        update(pageId) { it.copy(textStatus = status) }
        return true
    }

    override suspend fun markTextPending(documentId: DocumentId) {
        pagesOf(documentId).forEach { page -> pages[page.id] = page.copy(textStatus = TextStatus.PENDING) }
        changes.update { it + 1 }
    }
}

class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)
}

/**
 * Copies files instead of decoding; a quarter turn swaps width and height and the size limit scales them, like the
 * real renderer. [failWith] decides per call whether it throws instead (an IOException, an OutOfMemoryError...).
 */
class CopyingRenderer : PageRenderer {
    val rendered = mutableListOf<PageEdits>()
    val specs = mutableListOf<RenderSpec>()
    var failWith: (source: ImageInfo, spec: RenderSpec) -> Throwable? = { _, _ -> null }
    var beforeReturn: () -> Unit = {}

    override suspend fun render(source: ImageInfo, edits: PageEdits, spec: RenderSpec, out: File): ImageInfo {
        specs += spec
        failWith(source, spec)?.let { throw it }
        rendered += edits
        File(source.path).copyTo(out, overwrite = true)
        val turned = edits.rotation == Rotation.R90 || edits.rotation == Rotation.R270
        val limit = spec.maxLongEdgePx
        val scale = if (limit == null) 1.0 else minOf(1.0, limit.toDouble() / maxOf(source.widthPx, source.heightPx))
        val width = (source.widthPx * scale).roundToInt()
        val height = (source.heightPx * scale).roundToInt()
        beforeReturn()
        return ImageInfo(out.path, if (turned) height else width, if (turned) width else height, out.length(), "image/jpeg")
    }

    override suspend fun preview(source: ImageInfo, edits: PageEdits, maxLongEdgePx: Int): Bitmap =
        throw UnsupportedOperationException("previews are not used by the domain")

    override suspend fun thumbnail(image: ImageInfo, out: File, maxLongEdgePx: Int): File =
        File(image.path).copyTo(out, overwrite = true)
}

/** Scripted OCR: [answer] decides each recognition; [models] is what ensureModels emits. */
class FakeOcr : OcrEngine {
    val calls = mutableListOf<Pair<String, OcrScript>>()
    var answer: (ImageInfo, OcrScript) -> OcrOutcome = { image, _ ->
        OcrOutcome.Recognized("Invoice 42", emptyList(), image.widthPx, image.heightPx, 5, "fake/1")
    }
    val modelRequests = mutableListOf<Set<OcrScript>>()
    var models: () -> Flow<ModuleState> = { flowOf(ModuleState.Checking, ModuleState.Ready) }

    override suspend fun recognize(image: ImageInfo, script: OcrScript): OcrOutcome {
        calls += image.path to script
        return answer(image, script)
    }

    override fun ensureModels(scripts: Set<OcrScript>): Flow<ModuleState> {
        modelRequests += scripts
        return models()
    }
}

class FakeImporter(private val staging: File) : ImageImporter {
    var reject: (Uri) -> RejectReason? = { null }
    var throwFor: (Uri) -> Boolean = { false }

    override suspend fun import(uri: Uri, origin: PageOrigin): ImportedImage {
        if (throwFor(uri)) throw IllegalStateException("decoder crashed")
        reject(uri)?.let { return ImportedImage.Rejected(it) }
        val file = File.createTempFile("staged", ".jpg", staging).apply { writeText(uri.toString()) }
        return ImportedImage.Ok(heycam.core.model.StagedImage(file, 100, 140, "image/jpeg", origin))
    }
}

class RecordingSessions : ScanSessionStore by unused() {
    val discarded = mutableListOf<String>()
    override suspend fun discard(id: String) {
        discarded += id
    }
}
