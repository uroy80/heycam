package heycam.core.domain

import heycam.core.common.ApplicationScope
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.data.PageWorkStore
import heycam.core.data.SettingsRepository
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.RenderSpec
import heycam.core.model.DocumentId
import heycam.core.model.ImageInfo
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.TextStatus
import heycam.core.ocr.OcrEngine
import heycam.core.ocr.OcrOutcome
import heycam.core.ocr.ScriptResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single background pipeline (PLAN 3.7): one page at a time on a one-thread view of the default dispatcher, in
 * the application scope. For each page it renders the edits at full size, makes the thumbnail, then reads the text
 * once the render is current. Work is found through [PageWorkStore], so a page the process did not finish is simply
 * offered again at the next [kick].
 *
 * Text models: a missing model holds back the pages of that script (they stay PENDING) while the model downloads;
 * a download that fails offline is tried again when the phone is back online. Without Google Play services, or once a
 * model has failed [MAX_MODEL_ATTEMPTS] times, pages are marked FAILED so nothing waits for them; [retry] and [reread]
 * give the models fresh attempts.
 *
 * Nothing a page does may stop the worker: a failed render, even one out of memory, marks the page FAILED and the pass
 * goes on; any other error ends the pass and the next kick tries again.
 */
@Singleton
class QueuedPageProcessor @Inject constructor(
    private val work: PageWorkStore,
    private val documents: DocumentRepository,
    private val settings: SettingsRepository,
    private val renderer: PageRenderer,
    private val files: FileStore,
    private val ocr: OcrEngine,
    private val scripts: ScriptResolver,
    private val locales: DeviceLocales,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val log: HeycamLog,
    dispatchers: DispatcherProvider,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PageProcessor {

    private val worker = dispatchers.default.limitedParallelism(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    /** Held while one page is worked on; [DefaultScanImporter] and [reread] take it to change pages in between. */
    internal val pageLock = Mutex()

    private val _state = MutableStateFlow(ProcessorState(pagesToRender = 0, pagesToRead = 0, current = null, models = null))
    override val state: StateFlow<ProcessorState> = _state.asStateFlow()

    /** Kick tickets: a pass that starts after ticket n has seen every change made before kick n. */
    private val requested = AtomicLong(0)
    private val completed = MutableStateFlow(0L)

    /** Bumped after every page and pass, so [awaitIdle] can re-check without missing a wake-up. */
    private val progress = MutableStateFlow(0L)

    /**
     * Pages whose render failed in this process (page id, editsVersion). They are left alone while their text is
     * FAILED, so a bad page does not loop; Retry or Read again (back to PENDING) or a restart tries them again.
     */
    private val failedRenders: MutableSet<Pair<PageId, Int>> = ConcurrentHashMap.newKeySet()

    /** Scripts whose model is missing, with the download's latest state. */
    private val blocked = ConcurrentHashMap<OcrScript, ModuleState>()
    private val downloads = ConcurrentHashMap<OcrScript, Job>()
    private val modelAttempts = ConcurrentHashMap<OcrScript, Int>()

    /** Documents the user asked to read again; read even when automatic reading is off. */
    private val forced: MutableSet<DocumentId> = ConcurrentHashMap.newKeySet()

    /** Per-pass cache of document script overrides; [reread] drops its document's entry. */
    private val overrides = ConcurrentHashMap<DocumentId, Override>()

    /** Settings.ocrScript as of the current pass. */
    @Volatile private var ocrSetting: OcrScript? = null

    /** Set once text recognition turned out to be impossible on this phone (no Google Play services). */
    @Volatile private var unsupported: ModuleState.Unsupported? = null

    private data class Override(val script: OcrScript?)

    override fun kick() {
        request()
    }

    override fun retry() {
        modelAttempts.clear()
        kick()
    }

    private fun request(): Long {
        val ticket = requested.incrementAndGet()
        if (started.compareAndSet(false, true)) {
            scope.launch(worker) { for (signal in wake) drain() }
        }
        wake.trySend(Unit)
        return ticket
    }

    override suspend fun awaitIdle(documentIds: Collection<DocumentId>?) {
        val ids = documentIds?.toSet()
        val ticket = request()
        while (true) {
            val seen = progress.value
            if (completed.value >= ticket) return
            if (ids != null && !hasWorkFor(ids)) return
            progress.first { it != seen }
        }
    }

    override suspend fun reread(documentId: DocumentId, script: OcrScript?) {
        pageLock.withLock {
            documents.setScriptOverride(documentId, script)
            overrides.remove(documentId)
            forced += documentId
            work.markTextPending(documentId)
        }
        retry()
    }

    private suspend fun drain() {
        val ticket = requested.get()
        overrides.clear()
        // A script whose download stopped (failed, not running) gets another try in this pass.
        blocked.keys.filter { downloads[it]?.isActive != true }.forEach { blocked.remove(it) }
        publishModels()
        try {
            val current = settings.settings.first()
            ocrSetting = current.ocrScript
            while (true) {
                val renders = work.pagesNeedingRender(QUERY_LIMIT).filterNot(::gaveUpRendering)
                val texts = work.pagesNeedingText(QUERY_LIMIT)
                _state.update { it.copy(pagesToRender = renders.size, pagesToRead = texts.size) }
                val render = renders.firstOrNull()
                if (render != null) {
                    step(render) { render(it) }
                    continue
                }
                val text = texts.firstOrNull { readable(it) } ?: break
                step(text) { read(it, current.autoText) }
            }
            forced.retainAll(work.pagesNeedingText(QUERY_LIMIT).map { it.documentId }.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage trouble: stop this pass; the next kick tries again.
            log.e(TAG, "Page pass stopped", e)
        } catch (e: OutOfMemoryError) {
            // Renders handle their own; anything else short of memory stops this pass, never the worker or the app.
            log.e(TAG, "Page pass stopped: out of memory", e)
        } finally {
            _state.update { it.copy(current = null) }
            completed.update { maxOf(it, ticket) }
            progress.update { it + 1 }
        }
    }

    private suspend fun step(page: Page, block: suspend (Page) -> Unit) {
        _state.update { it.copy(current = page.documentId) }
        pageLock.withLock { block(page) }
        progress.update { it + 1 }
    }

    /** Text pages wait while their script's model downloads. */
    private suspend fun readable(page: Page): Boolean = blocked.isEmpty() || !blocked.containsKey(scriptFor(page.documentId))

    private suspend fun hasWorkFor(ids: Set<DocumentId>): Boolean =
        work.pagesNeedingRender(QUERY_LIMIT).any { it.documentId in ids && !gaveUpRendering(it) } ||
            work.pagesNeedingText(QUERY_LIMIT).any { it.documentId in ids && readable(it) }

    /** These edits failed to render and the page still shows that (FAILED); a Retry sets it PENDING again. */
    private fun gaveUpRendering(page: Page): Boolean =
        page.textStatus == TextStatus.FAILED && (page.id to page.editsVersion) in failedRenders

    private suspend fun render(page: Page) {
        val dir = files.pageDir(page.fileKey)
        val version = page.editsVersion
        val created = ArrayList<File>(2)
        failedRenders -= page.id to version
        try {
            val render = if (page.edits.isIdentity) {
                page.source
            } else {
                renderEdits(page, File(dir, "render-$version.jpg").also { created += it })
            }
            val thumb = File(dir, "thumb-$version.jpg").also { created += it }
            renderer.thumbnail(render, thumb)
            if (!work.saveRender(page.id, version, render, thumb.path)) created.forEach(File::delete) // edited meanwhile
        } catch (e: CancellationException) {
            created.forEach(File::delete)
            throw e
        } catch (e: Exception) {
            renderFailed(page, created, e)
        } catch (e: OutOfMemoryError) {
            renderFailed(page, created, e)
        }
    }

    /**
     * The edits at full size. A filtered render holds the bitmap and its pixel array at once, which a phone with a
     * small heap may not fit for a large page; it then gets the page at [FALLBACK_LONG_EDGE_PX] rather than none.
     */
    private suspend fun renderEdits(page: Page, out: File): ImageInfo =
        try {
            renderer.render(page.source, page.edits, RenderSpec(maxLongEdgePx = null), out)
        } catch (e: OutOfMemoryError) {
            if (max(page.source.widthPx, page.source.heightPx) <= FALLBACK_LONG_EDGE_PX) throw e
            log.w(TAG, "Full-size render of page ${page.id.value} ran out of memory; rendering it smaller", e)
            renderer.render(page.source, page.edits, RenderSpec(maxLongEdgePx = FALLBACK_LONG_EDGE_PX), out)
        }

    private suspend fun renderFailed(page: Page, created: List<File>, error: Throwable) {
        created.forEach(File::delete)
        log.w(TAG, "Render failed for page ${page.id.value}", error)
        failedRenders += page.id to page.editsVersion
        work.setTextStatus(page.id, page.editsVersion, TextStatus.FAILED)
    }

    private suspend fun read(page: Page, autoText: Boolean) {
        if (!autoText && page.documentId !in forced) {
            work.setTextStatus(page.id, page.editsVersion, TextStatus.SKIPPED)
            return
        }
        val script = scriptFor(page.documentId)
        val image = page.render ?: page.source
        when (val outcome = ocr.recognize(image, script)) {
            is OcrOutcome.Recognized -> {
                modelAttempts.remove(script)
                if (unsupported != null) {
                    unsupported = null // Play services arrived since
                    publishModels()
                }
                val text = PageText(
                    pageId = page.id,
                    script = script,
                    text = outcome.text,
                    lines = outcome.lines,
                    imageWidthPx = outcome.widthPx,
                    imageHeightPx = outcome.heightPx,
                    editsVersion = page.editsVersion,
                    engine = outcome.engine,
                    recognizedAt = clock.now(),
                )
                work.saveText(text) // false: the page changed meanwhile and is read again
            }
            is OcrOutcome.ModelNotReady -> modelMissing(page, script, outcome.state)
            is OcrOutcome.Failed -> {
                log.w(TAG, "Reading failed for page ${page.id.value}: ${outcome.errorClass}")
                work.setTextStatus(page.id, page.editsVersion, TextStatus.FAILED)
            }
        }
    }

    private suspend fun modelMissing(page: Page, script: OcrScript, state: ModuleState) {
        val attempts = modelAttempts.merge(script, 1, Int::plus) ?: 1
        if (state is ModuleState.Unsupported || attempts > MAX_MODEL_ATTEMPTS) {
            // No Play services, or the model keeps going missing right after installing: do not hold pages forever.
            if (state is ModuleState.Unsupported) unsupported = state
            _state.update { it.copy(models = state) }
            work.setTextStatus(page.id, page.editsVersion, TextStatus.FAILED)
            return
        }
        blocked[script] = state
        publishModels()
        if (downloads[script]?.isActive != true) downloads[script] = scope.launch { download(script) }
    }

    /** Fetches [script]'s model; offline failures wait for a network and try again with a growing pause. */
    private suspend fun download(script: OcrScript) {
        try {
            fetch(script)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Model download for $script stopped", e)
        }
    }

    private suspend fun fetch(script: OcrScript) {
        var pause = FIRST_RETRY_MS
        repeat(MAX_NETWORK_RETRIES) {
            var last: ModuleState? = null
            ocr.ensureModels(setOf(script)).collect { state ->
                last = state
                blocked[script] = state
                publishModels()
            }
            when (val result = last) {
                ModuleState.Ready -> {
                    blocked.remove(script)
                    publishModels()
                    kick()
                    return
                }
                is ModuleState.Failed -> if (result.reason != ModuleFailure.NO_NETWORK) return // next kick retries
                is ModuleState.Unsupported -> {
                    kick() // its pages are marked FAILED rather than left waiting
                    return
                }
                else -> return
            }
            network.awaitOnline()
            delay(pause)
            pause = (pause * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun publishModels() {
        _state.update { it.copy(models = blocked.values.firstOrNull() ?: unsupported) }
    }

    private suspend fun scriptFor(documentId: DocumentId): OcrScript {
        val override = overrides[documentId]
            ?: Override(documents.observeDocument(documentId).first()?.scriptOverride).also { overrides[documentId] = it }
        return scripts.resolve(override.script, ocrSetting, locales.current())
    }

    private companion object {
        const val TAG = "PageProcessor"
        const val QUERY_LIMIT = 1_000
        const val MAX_MODEL_ATTEMPTS = 3
        const val FALLBACK_LONG_EDGE_PX = 3_000
        const val MAX_NETWORK_RETRIES = 6
        const val FIRST_RETRY_MS = 15_000L
        const val MAX_RETRY_MS = 600_000L
    }
}
