package heycam.feature.export

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.SettingsRepository
import heycam.core.domain.PageProcessor
import heycam.core.export.ExportFailure
import heycam.core.export.ExportProgress
import heycam.core.export.ExportRequest
import heycam.core.export.ExportResult
import heycam.core.export.ExportService
import heycam.core.export.ExportTarget
import heycam.core.export.PrintService
import heycam.core.export.TextPendingPolicy
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.Margin
import heycam.core.model.PageSize
import heycam.core.model.SizePreset
import heycam.core.model.TextStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the sheet was opened for. [compress] comes from Tools → Compress and presets the size to Small. */
data class ExportArgs(val documentIds: List<Long>, val compress: Boolean = false)

data class ExportDocument(val id: DocumentId, val title: String, val pageCount: Int, val pendingTextPages: Int)

/** Actions that may need recognised text first. Printing never does. */
enum class ExportAction { SAVE, SHARE }

enum class ExportError { NO_SPACE, WRITE_FAILED, SOURCE_MISSING, PRINT_FAILED, NO_APP }

data class ExportUiState(
    val loading: Boolean = true,
    val documents: List<ExportDocument> = emptyList(),
    val format: ExportFormat = ExportFormat.PDF,
    val size: SizePreset = SizePreset.STANDARD,
    val pageSize: PageSize = PageSize.FIT,
    val margin: Margin = Margin.NONE,
    val searchable: Boolean = true,
    val fileName: String = "",
    /** Bytes per preset: missing while it is being measured, null when it could not be measured. */
    val estimates: Map<SizePreset, Long?> = emptyMap(),
    val running: Boolean = false,
    val progress: ExportProgress? = null,
    /** Non-null while the "wait / export without text" question is showing. */
    val textPendingPages: Int? = null,
    val error: ExportError? = null,
) {
    val unavailable: Boolean get() = !loading && documents.isEmpty()
    val pageCount: Int get() = documents.sumOf { it.pageCount }
    val singleDocument: Boolean get() = documents.size == 1
    val fileCount: Int get() = if (format.onePerPage) pageCount else documents.size
    val canExport: Boolean get() = !loading && documents.isNotEmpty() && !running && textPendingPages == null
}

sealed interface ExportEvent {
    data class PickFile(val mime: String, val suggestedName: String) : ExportEvent
    data object PickFolder : ExportEvent
    data class Share(val chooser: Intent) : ExportEvent
    data object Print : ExportEvent
    data class Saved(val files: Int) : ExportEvent
}

internal val ExportFormat.onePerPage: Boolean get() = this == ExportFormat.JPEG || this == ExportFormat.PNG

internal val ExportFormat.mime: String
    get() = when (this) {
        ExportFormat.PDF -> "application/pdf"
        ExportFormat.JPEG -> "image/jpeg"
        ExportFormat.PNG -> "image/png"
        ExportFormat.TEXT -> "text/plain"
    }

internal val ExportFormat.extension: String
    get() = when (this) {
        ExportFormat.PDF -> ".pdf"
        ExportFormat.JPEG -> ".jpg"
        ExportFormat.PNG -> ".png"
        ExportFormat.TEXT -> ".txt"
    }

/** Searchable PDFs and text files carry recognised text; images do not. */
private fun needsText(format: ExportFormat, searchable: Boolean) =
    format == ExportFormat.TEXT || (format == ExportFormat.PDF && searchable)

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = ExportViewModel.Factory::class)
class ExportViewModel @AssistedInject constructor(
    @Assisted args: ExportArgs,
    private val savedState: SavedStateHandle,
    documentRepository: DocumentRepository,
    private val settingsRepository: SettingsRepository,
    private val exportService: ExportService,
    private val printService: PrintService,
    private val processor: PageProcessor,
    private val log: HeycamLog,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(args: ExportArgs): ExportViewModel
    }

    private data class Choices(
        val format: ExportFormat,
        val size: SizePreset,
        val pageSize: PageSize,
        val margin: Margin,
        val searchable: Boolean,
        val fileName: String?,   // null = not edited, the document title is used
    )

    private data class PendingAsk(val action: ExportAction, val pages: Int, val target: ExportTarget?)

    private data class RunState(
        val running: Boolean = false,
        val progress: ExportProgress? = null,
        val ask: PendingAsk? = null,
        val error: ExportError? = null,
    )

    private data class EstimateKey(
        val documents: List<Pair<DocumentId, Int>>,
        val format: ExportFormat,
        val pageSize: PageSize,
        val margin: Margin,
        val searchable: Boolean,
    )

    private val ids = args.documentIds.distinct().map(::DocumentId)
    private val compress = args.compress

    /** Trashed or deleted documents drop out: they cannot be exported (PLAN F12). */
    private val documents: StateFlow<List<Document>?> =
        (if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map(documentRepository::observeDocument)) { docs ->
            docs.filterNotNull().filter { it.trashedAt == null }
        })
            .catch { e ->
                log.e(TAG, "documents unavailable: ${e.javaClass.simpleName}")
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until restored from [savedState] or read from the export defaults. */
    private val choices = MutableStateFlow(savedState.restoreChoices())
    private val estimates = MutableStateFlow<Map<SizePreset, Long?>>(emptyMap())
    private val run = MutableStateFlow(RunState())

    /**
     * Policy chosen before a picker or the print dialog opened; used when it returns. Kept in [savedState] with the
     * choices: the system may end the process while its "Save to" picker is open.
     */
    private var policyAfterPicker: TextPendingPolicy
        get() = savedState.get<String>(KEY_POLICY).toEnum<TextPendingPolicy>() ?: TextPendingPolicy.ASK
        set(value) {
            savedState[KEY_POLICY] = value.name
        }

    private val _events = Channel<ExportEvent>(Channel.BUFFERED)
    val events: Flow<ExportEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ExportUiState> = combine(documents, choices, estimates, run) { docs, c, est, r ->
        if (docs == null || c == null) {
            ExportUiState(loading = true)
        } else {
            ExportUiState(
                loading = false,
                documents = docs.map { d ->
                    ExportDocument(d.id, d.title, d.pages.size, d.pages.count { it.textStatus == TextStatus.PENDING })
                },
                format = c.format,
                size = c.size,
                pageSize = c.pageSize,
                margin = c.margin,
                searchable = c.searchable,
                fileName = c.fileName ?: docs.singleOrNull()?.title.orEmpty(),
                estimates = est,
                running = r.running,
                progress = r.progress,
                textPendingPages = r.ask?.pages,
                error = r.error,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExportUiState())

    init {
        // Restored choices win: they are what the user saw before the process ended.
        if (choices.value == null) viewModelScope.launch { setChoices(defaultChoices()) }
        viewModelScope.launch {
            combine(documents.filterNotNull(), choices.filterNotNull()) { docs, c ->
                EstimateKey(docs.map { it.id to it.pages.size }, c.format, c.pageSize, c.margin, c.searchable)
            }
                .distinctUntilChanged()
                .debounce(ESTIMATE_DEBOUNCE_MS)
                .collectLatest { key -> measure(key) }
        }
    }

    fun setFormat(format: ExportFormat) = choose { it.copy(format = format) }
    fun setSize(size: SizePreset) = choose { it.copy(size = size) }
    fun setPageSize(pageSize: PageSize) = choose { it.copy(pageSize = pageSize) }
    fun setMargin(margin: Margin) = choose { it.copy(margin = margin) }
    fun setSearchable(searchable: Boolean) = choose { it.copy(searchable = searchable) }
    fun setFileName(name: String) = choose { it.copy(fileName = name) }

    fun save() = start(ExportAction.SAVE)
    fun share() = start(ExportAction.SHARE)

    /** Printing renders the pages; it never needs the recognised text, so it does not ask. */
    fun print() {
        if (!canStart()) return
        run.update { it.copy(error = null) }
        policyAfterPicker = TextPendingPolicy.WITHOUT_TEXT
        _events.trySend(ExportEvent.Print)
    }

    fun answerTextPending(wait: Boolean) {
        val ask = run.value.ask ?: return
        run.update { it.copy(ask = null) }
        val policy = if (wait) TextPendingPolicy.WAIT else TextPendingPolicy.WITHOUT_TEXT
        if (ask.target != null) export(ask.action, ask.target, policy) else proceed(ask.action, policy)
    }

    /**
     * Back or a tap outside the question: nothing is exported. When the "Save to" picker had already made the
     * file, it would stay behind empty, so it goes.
     */
    fun dismissTextPending() {
        val ask = run.value.ask ?: return
        run.update { it.copy(ask = null) }
        val picked = ask.target as? ExportTarget.Document ?: return
        viewModelScope.launch {
            try {
                exportService.discard(picked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "could not remove the picked file: ${e.javaClass.simpleName}")
            }
        }
    }

    fun onFilePicked(uri: Uri?) {
        if (uri != null) exportWhenLoaded(ExportTarget.Document(uri))
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri != null) exportWhenLoaded(ExportTarget.Folder(uri))
    }

    /** The system had no activity for a picker, chooser or print dialog. */
    fun onNoApp() = run.update { it.copy(running = false, progress = null, error = ExportError.NO_APP) }

    /** Runs in the caller's (composition) scope so the Activity is never held past the screen. */
    suspend fun printNow(activity: Activity) {
        val request = request(policyAfterPicker) ?: return
        if (run.value.running) return
        run.update { RunState(running = true) }
        try {
            printService.print(activity, request.copy(options = request.options.copy(format = ExportFormat.PDF)))
            run.update { it.copy(running = false) }
        } catch (e: CancellationException) {
            run.update { it.copy(running = false) }
            throw e
        } catch (e: Exception) {
            log.e(TAG, "print failed: ${e.javaClass.simpleName}")
            run.update { it.copy(running = false, error = ExportError.PRINT_FAILED) }
        }
    }

    private fun choose(transform: (Choices) -> Choices) {
        if (run.value.running) return
        choices.value?.let { setChoices(transform(it)) }
        run.update { it.copy(error = null) }
    }

    private fun setChoices(c: Choices) {
        choices.value = c
        savedState.saveChoices(c)
    }

    private suspend fun defaultChoices(): Choices {
        val defaults = try {
            settingsRepository.settings.first().exportDefaults
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "settings unavailable: ${e.javaClass.simpleName}")
            ExportOptions()
        }
        return Choices(
            format = defaults.format,
            size = if (compress) SizePreset.SMALL else defaults.size,
            pageSize = defaults.pageSize,
            margin = defaults.margin,
            searchable = defaults.searchable,
            fileName = null,
        )
    }

    /**
     * A picker's result can arrive before anything has loaded: after the process was ended while the picker was
     * open, the restored sheet gets it as soon as it starts. The export then waits for the choices and the
     * documents instead of being dropped.
     */
    private fun exportWhenLoaded(target: ExportTarget) {
        val policy = policyAfterPicker
        if (choices.value != null && documents.value != null) {
            export(ExportAction.SAVE, target, policy)
        } else {
            viewModelScope.launch {
                choices.filterNotNull().first()
                documents.filterNotNull().first()
                export(ExportAction.SAVE, target, policy)
            }
        }
    }

    /** Reads the source flows, not [uiState], so a tap right after a change acts on the new choices. */
    private fun canStart(): Boolean {
        val r = run.value
        return choices.value != null && !documents.value.isNullOrEmpty() && !r.running && r.ask == null
    }

    private fun start(action: ExportAction) {
        if (!canStart()) return
        val c = choices.value ?: return
        val docs = documents.value.orEmpty()
        val pending = if (needsText(c.format, c.searchable)) {
            docs.sumOf { d -> d.pages.count { it.textStatus == TextStatus.PENDING } }
        } else {
            0
        }
        if (pending > 0) {
            run.update { it.copy(ask = PendingAsk(action, pending, target = null), error = null) }
        } else {
            proceed(action, TextPendingPolicy.ASK)
        }
    }

    private fun proceed(action: ExportAction, policy: TextPendingPolicy) {
        run.update { it.copy(error = null) }
        when (action) {
            ExportAction.SAVE -> {
                val request = request(policy) ?: return
                policyAfterPicker = policy
                val docs = documents.value.orEmpty()
                val format = request.options.format
                val files = if (format.onePerPage) docs.sumOf { it.pages.size } else docs.size
                val event = if (files == 1) {
                    ExportEvent.PickFile(format.mime, exportService.suggestedFileName(docs.single().title, request.options))
                } else {
                    ExportEvent.PickFolder
                }
                _events.trySend(event)
            }
            ExportAction.SHARE -> export(action, ExportTarget.Share, policy)
        }
    }

    private fun export(action: ExportAction, target: ExportTarget, policy: TextPendingPolicy) {
        val request = request(policy) ?: return
        if (run.value.running) return
        run.value = RunState(running = true)
        // Waiting needs something reading: pages back from the trash, or left over from an earlier run, may be
        // waiting with no pass queued.
        if (request.whenTextPending == TextPendingPolicy.WAIT) processor.kick()
        viewModelScope.launch {
            val result = try {
                exportService.export(request, target) { progress -> run.update { it.copy(progress = progress) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "export failed: ${e.javaClass.simpleName}")
                ExportResult.Failed(ExportFailure.WRITE_FAILED)
            }
            run.update { it.copy(running = false, progress = null) }
            when (result) {
                is ExportResult.Saved -> _events.send(ExportEvent.Saved(result.uris.size))
                is ExportResult.ReadyToShare -> _events.send(ExportEvent.Share(result.chooser))
                is ExportResult.TextPending -> run.update { it.copy(ask = PendingAsk(action, result.pagesPending, target)) }
                is ExportResult.Failed -> run.update { it.copy(error = result.reason.toError()) }
            }
        }
    }

    private fun request(policy: TextPendingPolicy): ExportRequest? {
        val c = choices.value ?: return null
        val docs = documents.value?.takeIf { it.isNotEmpty() } ?: return null
        val options = ExportOptions(
            format = c.format,
            size = c.size,
            pageSize = c.pageSize,
            margin = c.margin,
            searchable = c.searchable,
            fileName = if (docs.size == 1) c.fileName?.trim()?.takeIf { it.isNotEmpty() } else null,
        )
        val effective = if (needsText(c.format, c.searchable)) policy else TextPendingPolicy.WITHOUT_TEXT
        return ExportRequest(docs.map { it.id }, options, effective)
    }

    /**
     * One preset at a time: each estimate encodes a page at full size, and only one such bitmap may be alive.
     * Text files have no size choice, so nothing is measured for them.
     */
    private suspend fun measure(key: EstimateKey) {
        estimates.value = emptyMap()
        if (key.documents.isEmpty() || key.format == ExportFormat.TEXT) return
        for (preset in SizePreset.entries) {
            val request = ExportRequest(
                documentIds = key.documents.map { it.first },
                options = ExportOptions(
                    format = key.format,
                    size = preset,
                    pageSize = key.pageSize,
                    margin = key.margin,
                    searchable = key.searchable,
                ),
                whenTextPending = TextPendingPolicy.WITHOUT_TEXT,
            )
            try {
                val bytes = exportService.estimate(request).bytes
                estimates.update { it + (preset to bytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "estimate failed: ${e.javaClass.simpleName}")
                estimates.update { it + (preset to null) }
            }
        }
    }

    private fun ExportFailure.toError(): ExportError? = when (this) {
        ExportFailure.NO_SPACE -> ExportError.NO_SPACE
        ExportFailure.WRITE_FAILED -> ExportError.WRITE_FAILED
        ExportFailure.SOURCE_MISSING -> ExportError.SOURCE_MISSING
        ExportFailure.CANCELLED -> null
    }

    private fun SavedStateHandle.saveChoices(c: Choices) {
        set(KEY_FORMAT, c.format.name)
        set(KEY_SIZE, c.size.name)
        set(KEY_PAGE_SIZE, c.pageSize.name)
        set(KEY_MARGIN, c.margin.name)
        set(KEY_SEARCHABLE, c.searchable)
        if (c.fileName != null) set(KEY_FILE_NAME, c.fileName) else remove<String>(KEY_FILE_NAME)
    }

    /** Null when nothing was saved, or when a saved value no longer names a choice. */
    private fun SavedStateHandle.restoreChoices(): Choices? {
        return Choices(
            format = get<String>(KEY_FORMAT).toEnum<ExportFormat>() ?: return null,
            size = get<String>(KEY_SIZE).toEnum<SizePreset>() ?: return null,
            pageSize = get<String>(KEY_PAGE_SIZE).toEnum<PageSize>() ?: return null,
            margin = get<String>(KEY_MARGIN).toEnum<Margin>() ?: return null,
            searchable = get<Boolean>(KEY_SEARCHABLE) ?: return null,
            fileName = get<String>(KEY_FILE_NAME),
        )
    }

    private companion object {
        const val TAG = "Export"
        const val ESTIMATE_DEBOUNCE_MS = 250L
        const val KEY_FORMAT = "export.format"
        const val KEY_SIZE = "export.size"
        const val KEY_PAGE_SIZE = "export.pageSize"
        const val KEY_MARGIN = "export.margin"
        const val KEY_SEARCHABLE = "export.searchable"
        const val KEY_FILE_NAME = "export.fileName"
        const val KEY_POLICY = "export.policyAfterPicker"
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(): E? = enumValues<E>().firstOrNull { it.name == this }
