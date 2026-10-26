package heycam.feature.scan.entry

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.ScanSessionStore
import heycam.core.data.SettingsRepository
import heycam.core.domain.ScanImporter
import heycam.core.model.ImportTarget
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import heycam.core.model.PageOrigin
import heycam.core.model.StagedImage
import heycam.core.model.UnsupportedReason
import heycam.core.scanner.ScanRequest
import heycam.core.scanner.ScanResult
import heycam.core.scanner.ScannerLauncher
import heycam.feature.scan.review.withDefaultFilter
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/** Feedback for whoever hosts the scan entry; mapped to the public ScanMessage with localised text. */
internal sealed interface EntryNotice {
    data class Unavailable(val reason: UnsupportedReason) : EntryNotice
    data class Rejected(val count: Int) : EntryNotice
    data object Failed : EntryNotice
}

internal sealed interface EntryEvent {
    /** Ask the host to start Google's scanner; needs the current Activity. */
    data class StartScanner(val request: ScanRequest) : EntryEvent
    data object PickPhotos : EntryEvent
    data class Staged(val sessionId: String) : EntryEvent
    data class Notice(val notice: EntryNotice) : EntryEvent
}

/**
 * Scan entry (F1–F3): scanner, Photo Picker and shared images all end in a persisted ScanSession for Review.
 * Activity-scoped, so it outlives navigation; the pending target sits in SavedStateHandle so a scan survives
 * process death while Google's scanner is in front.
 */
@HiltViewModel
internal class ScanEntryViewModel @Inject constructor(
    private val launcher: ScannerLauncher,
    private val importer: ScanImporter,
    private val sessions: ScanSessionStore,
    private val settings: SettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _events = Channel<EntryEvent>(Channel.BUFFERED)
    val events: Flow<EntryEvent> = _events.receiveAsFlow()

    private val _scanner = MutableStateFlow<ModuleState?>(null)
    /** Scanner module download or unavailability for the Home bar (S6); null when there is nothing to say. */
    val scanner: StateFlow<ModuleState?> = _scanner.asStateFlow()

    private var installJob: Job? = null
    private var pendingRequest: ScanRequest? = null

    /** True while Google's scanner or the Photo Picker is open, so a double tap cannot open two. */
    private var awaitingResult = false

    private var pendingTarget: ImportTarget?
        get() = ScanTargets.decode(savedState[KEY_TARGET])
        set(value) { savedState[KEY_TARGET] = value?.let(ScanTargets::encode) }

    fun scan(target: ImportTarget, request: ScanRequest) {
        if (awaitingResult) return
        pendingTarget = target
        pendingRequest = request
        _events.trySend(EntryEvent.StartScanner(request))
    }

    /** Returns what to launch, or null when the outcome was handled here (fake result, download, unsupported). */
    suspend fun start(activity: Activity, request: ScanRequest): IntentSenderRequest? {
        if (awaitingResult) return null
        return when (val outcome = launcher.prepare(activity, request)) {
            is StartOutcome.Launch -> outcome.request.also { awaitingResult = true }
            is StartOutcome.Finished -> { handle(outcome.result); null }
            is StartOutcome.NotReady -> { notReady(outcome.state); null }
        }
    }

    fun onScannerResult(resultCode: Int, data: Intent?) {
        awaitingResult = false
        viewModelScope.launch { handle(launcher.finishSafely(resultCode, data)) }
    }

    fun importPhotos(target: ImportTarget) {
        if (awaitingResult) return
        pendingTarget = target
        _events.trySend(EntryEvent.PickPhotos)
    }

    /** The host opened the Photo Picker. */
    fun onPickerOpened() { awaitingResult = true }

    fun onPhotosPicked(uris: List<Uri>) {
        awaitingResult = false
        if (uris.isEmpty()) {
            pendingTarget = null
            return
        }
        viewModelScope.launch { stageUris(uris, PageOrigin.PHOTO_PICKER, refused = 0) }
    }

    /** Neither the scanner nor the picker could be opened (nothing on the phone handles it). */
    fun onLaunchFailed() {
        awaitingResult = false
        pendingTarget = null
        _events.trySend(EntryEvent.Notice(EntryNotice.Failed))
    }

    /** Images shared from other apps (F3): content:// only (P7), always a new document. */
    fun importShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingTarget = ImportTarget.NewDocument()
        val content = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        viewModelScope.launch { stageUris(content, PageOrigin.SHARE, refused = uris.size - content.size) }
    }

    fun retryScanner() = install()

    fun dismissScanner() {
        installJob?.cancel()
        _scanner.value = null
    }

    private suspend fun notReady(state: ModuleState) {
        when (state) {
            ModuleState.Ready -> Unit
            is ModuleState.Unsupported -> {
                pendingTarget = null
                _scanner.value = state
                _events.send(EntryEvent.Notice(EntryNotice.Unavailable(state.reason)))
            }
            else -> install()
        }
    }

    /** Installs the scanner module with progress; when it is ready the pending scan starts by itself. */
    private fun install() {
        installJob?.cancel()
        _scanner.value = ModuleState.Checking
        installJob = viewModelScope.launch {
            try {
                launcher.install()
                    .transformWhile { emit(it); it != ModuleState.Ready && it !is ModuleState.Unsupported }
                    .collect { state ->
                        when (state) {
                            ModuleState.Ready -> {
                                _scanner.value = null
                                pendingRequest?.let { _events.send(EntryEvent.StartScanner(it)) }
                            }
                            is ModuleState.Unsupported -> notReady(state)
                            else -> _scanner.value = state
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scanner.value = ModuleState.Failed(ModuleFailure.UNKNOWN, retryable = true)
            }
        }
    }

    private suspend fun handle(result: ScanResult) {
        when (result) {
            is ScanResult.Pages -> if (result.images.isNotEmpty()) openSession(result.images) else pendingTarget = null
            ScanResult.Cancelled -> pendingTarget = null
            is ScanResult.Failed -> {
                pendingTarget = null
                _events.send(EntryEvent.Notice(EntryNotice.Failed))
            }
        }
    }

    private suspend fun stageUris(uris: List<Uri>, origin: PageOrigin, refused: Int) {
        val images: List<StagedImage>
        val rejected: Int
        if (uris.isEmpty()) {
            images = emptyList()
            rejected = refused
        } else {
            val result = try {
                importer.stage(uris, origin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingTarget = null
                _events.send(EntryEvent.Notice(EntryNotice.Failed))
                return
            }
            images = result.images
            rejected = result.rejected + refused
        }
        if (rejected > 0) _events.send(EntryEvent.Notice(EntryNotice.Rejected(rejected)))
        if (images.isNotEmpty()) openSession(images) else pendingTarget = null
    }

    /** Persists the pages as a session (default filter applied) and hands its id to Review. */
    private suspend fun openSession(images: List<StagedImage>) {
        val target = pendingTarget ?: ImportTarget.NewDocument()
        val pages = if (target is ImportTarget.Replace) images.take(1) else images
        try {
            val created = sessions.create(target, pages)
            val session = created.withDefaultFilter(settings.settings.first().defaultFilter)
            if (session != created) sessions.update(session)
            pendingTarget = null
            _events.send(EntryEvent.Staged(session.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pendingTarget = null
            _events.send(EntryEvent.Notice(EntryNotice.Failed))
        }
    }

    private companion object {
        const val KEY_TARGET = "scanTarget"
    }
}
