package heycam.feature.scan.review

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.domain.CommitResult
import heycam.core.domain.ScanImporter
import heycam.core.imaging.PageRenderer
import heycam.core.model.DocumentId
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleState
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageOrigin
import heycam.core.model.ScanSession
import heycam.core.model.StagedImage
import heycam.core.model.StagedPage
import heycam.core.model.UnsupportedReason
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScannerLauncher
import heycam.feature.scan.ReviewRoute
import heycam.feature.scan.entry.StartOutcome
import heycam.feature.scan.entry.finishSafely
import heycam.feature.scan.entry.prepare
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What Review is doing; buttons are off while it is not [IDLE]. */
internal enum class ReviewBusy { IDLE, ADDING, SAVING }

internal data class ReviewUiState(
    val loading: Boolean = true,
    val session: ScanSession? = null,
    val current: Int = 0,
    val busy: ReviewBusy = ReviewBusy.IDLE,
) {
    val pages: List<StagedPage> get() = session?.pages.orEmpty()
    val currentPage: StagedPage? get() = pages.getOrNull(current)
    val replacing: Boolean get() = session?.target is ImportTarget.Replace
    val canAdd: Boolean get() = session != null && !replacing
    val canDelete: Boolean get() = pages.size > 1
}

internal sealed interface ReviewMessage {
    data object Failed : ReviewMessage
    data object ScannerNotReady : ReviewMessage
    data class ScannerUnavailable(val reason: UnsupportedReason) : ReviewMessage
    data class Rejected(val count: Int) : ReviewMessage
}

internal sealed interface ReviewEvent {
    /** Committed. [newDocument] is false for "add pages" and "retake", whose document is already open below. */
    data class Saved(val documentId: DocumentId, val newDocument: Boolean) : ReviewEvent
    /** Discarded, or the session no longer exists. */
    data object Closed : ReviewEvent
    data class LaunchScanner(val request: IntentSenderRequest) : ReviewEvent
    data class Message(val message: ReviewMessage) : ReviewEvent
}

/**
 * Review (S7, F1–F3): the staged pages large, a live filter strip, "use on all pages", rotate, delete, Add page,
 * Discard and Save. Every change is written back to the session file so the screen survives process death.
 */
@HiltViewModel
internal class ReviewViewModel @Inject constructor(
    private val sessions: ScanSessionStore,
    private val importer: ScanImporter,
    private val renderer: PageRenderer,
    private val launcher: ScannerLauncher,
    private val settings: SettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val sessionId = savedState.toRoute<ReviewRoute>().sessionId

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val _events = Channel<ReviewEvent>(Channel.BUFFERED)
    val events: Flow<ReviewEvent> = _events.receiveAsFlow()

    /** Serialises session writes with commit/discard; once [closed], late writes are dropped. */
    private val writeLock = Mutex()
    private var closed = false
    private var installJob: Job? = null

    private val previews = object : LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: PreviewKey, value: Bitmap) = value.allocationByteCount
    }

    init {
        viewModelScope.launch {
            val session = try {
                sessions.get(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (session == null || session.pages.isEmpty()) {
                _events.send(ReviewEvent.Closed)
            } else {
                val current = (savedState.get<Int>(KEY_CURRENT) ?: 0).coerceIn(0, session.pages.lastIndex)
                _state.value = ReviewUiState(loading = false, session = session, current = current)
            }
        }
    }

    fun select(index: Int) {
        val pages = _state.value.pages
        if (index !in pages.indices || index == _state.value.current) return
        savedState[KEY_CURRENT] = index
        _state.update { it.copy(current = index) }
    }

    fun setFilter(filter: PageFilter) = editCurrent { it.copy(filter = filter) }

    fun rotate() = editCurrent { it.copy(rotation = it.rotation.clockwise()) }

    /** "Use Auto on all 3 pages". */
    fun applyToAll(filter: PageFilter) = editPages { pages -> pages.map { it.copy(edits = it.edits.copy(filter = filter)) } }

    fun deleteCurrent() {
        val s = _state.value
        if (!s.canDelete || s.busy != ReviewBusy.IDLE) return
        val index = s.current
        editPages { pages -> pages.filterIndexed { i, _ -> i != index } }
        val next = index.coerceAtMost(_state.value.pages.lastIndex)
        savedState[KEY_CURRENT] = next
        _state.update { it.copy(current = next) }
    }

    /** Starts Google's scanner for more pages; the host launches what comes back in [ReviewEvent.LaunchScanner]. */
    fun addFromScanner(activity: Activity) {
        if (!_state.value.canAdd || _state.value.busy != ReviewBusy.IDLE) return
        setBusy(ReviewBusy.ADDING)
        viewModelScope.launch {
            when (val outcome = launcher.prepare(activity, ScanRequest())) {
                is StartOutcome.Launch -> _events.send(ReviewEvent.LaunchScanner(outcome.request))
                is StartOutcome.Finished -> onScanResult(outcome.result)
                is StartOutcome.NotReady -> {
                    setBusy(ReviewBusy.IDLE)
                    val state = outcome.state
                    if (state is ModuleState.Unsupported) {
                        message(ReviewMessage.ScannerUnavailable(state.reason))
                    } else {
                        message(ReviewMessage.ScannerNotReady)
                        installInBackground()
                    }
                }
            }
        }
    }

    fun onScannerResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch { onScanResult(launcher.finishSafely(resultCode, data)) }
    }

    fun onPickerOpened() = setBusy(ReviewBusy.ADDING)

    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            setBusy(ReviewBusy.IDLE)
            return
        }
        setBusy(ReviewBusy.ADDING)
        viewModelScope.launch {
            try {
                val staged = importer.stage(uris, PageOrigin.PHOTO_PICKER)
                if (staged.rejected > 0) message(ReviewMessage.Rejected(staged.rejected))
                append(staged.images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(ReviewMessage.Failed)
            } finally {
                setBusy(ReviewBusy.IDLE)
            }
        }
    }

    fun onLaunchFailed() {
        setBusy(ReviewBusy.IDLE)
        message(ReviewMessage.Failed)
    }

    /**
     * Commits the session. Once started the commit always runs to the end, even if the screen goes away meanwhile:
     * cancelling it half way would roll the pages back into a staging session that nothing can reopen.
     */
    fun save() {
        val session = _state.value.session ?: return
        if (_state.value.busy != ReviewBusy.IDLE) return
        setBusy(ReviewBusy.SAVING)
        viewModelScope.launch {
            val result = try {
                withContext(NonCancellable) { commitAndClear(session) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(ReviewBusy.IDLE)
                message(ReviewMessage.Failed)
                return@launch
            }
            _events.send(ReviewEvent.Saved(result.documentId, session.target is ImportTarget.NewDocument))
        }
    }

    private suspend fun commitAndClear(session: ScanSession): CommitResult {
        val result = writeLock.withLock {
            importer.commit(session).also { closed = true }
        }
        try {
            sessions.discard(session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The document is saved; leftover staging is swept at the next start.
        }
        return result
    }

    fun discard() {
        if (_state.value.busy == ReviewBusy.SAVING) return
        viewModelScope.launch {
            writeLock.withLock {
                closed = true
                try {
                    sessions.discard(sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing to keep; leftover staging is swept at the next start.
                }
            }
            _events.send(ReviewEvent.Closed)
        }
    }

    /** A downsampled render of [page] with [edits]; cached, null if the image cannot be read. */
    suspend fun preview(page: StagedPage, edits: PageEdits, maxLongEdgePx: Int): Bitmap? {
        val key = PreviewKey(page.image.file.path, edits, maxLongEdgePx)
        previews.get(key)?.let { return it }
        return try {
            renderer.preview(page.image.asImageInfo(), edits, maxLongEdgePx).also { previews.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun onScanResult(result: ScanResult) {
        when (result) {
            is ScanResult.Pages -> append(result.images)
            ScanResult.Cancelled -> Unit
            is ScanResult.Failed -> message(ReviewMessage.Failed)
        }
        setBusy(ReviewBusy.IDLE)
    }

    private suspend fun append(images: List<StagedImage>) {
        if (images.isEmpty()) return
        val filter = try {
            settings.settings.first().defaultFilter
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PageFilter.ORIGINAL
        }
        val first = _state.value.pages.size
        editPages { it + images.asPages(filter) }
        savedState[KEY_CURRENT] = first
        _state.update { it.copy(current = first) }
    }

    private fun editCurrent(transform: (PageEdits) -> PageEdits) {
        val index = _state.value.current
        editPages { pages -> pages.mapIndexed { i, p -> if (i == index) p.copy(edits = transform(p.edits)) else p } }
    }

    private fun editPages(transform: (List<StagedPage>) -> List<StagedPage>) {
        val session = _state.value.session ?: return
        if (_state.value.busy == ReviewBusy.SAVING) return
        val updated = session.copy(pages = transform(session.pages))
        if (updated == session) return
        _state.update { it.copy(session = updated) }
        persist()
    }

    private fun persist() {
        viewModelScope.launch {
            writeLock.withLock {
                if (closed) return@withLock
                val latest = _state.value.session ?: return@withLock
                try {
                    sessions.update(latest)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message(ReviewMessage.Failed)
                }
            }
        }
    }

    /** The scanner module was missing: fetch it quietly so the next "Add page" works. */
    private fun installInBackground() {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            try {
                launcher.install()
                    .transformWhile { emit(it); it != ModuleState.Ready && it !is ModuleState.Unsupported && it !is ModuleState.Failed }
                    .collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next "Add page" reports it again.
            }
        }
    }

    private fun setBusy(busy: ReviewBusy) = _state.update { it.copy(busy = busy) }

    private fun message(message: ReviewMessage) {
        _events.trySend(ReviewEvent.Message(message))
    }

    private data class PreviewKey(val path: String, val edits: PageEdits, val maxPx: Int)

    private companion object {
        const val KEY_CURRENT = "current"
        val PREVIEW_CACHE_BYTES = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    }
}
