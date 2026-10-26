package heycam.feature.document

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.DocumentRepository
import heycam.core.data.FolderRepository
import heycam.core.data.PageTextEditor
import heycam.core.data.PageWorkStore
import heycam.core.domain.PageProcessor
import heycam.core.export.ExportFailure
import heycam.core.export.ExportRequest
import heycam.core.export.ExportResult
import heycam.core.export.ExportService
import heycam.core.export.ExportTarget
import heycam.core.export.TextPendingPolicy
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.ExportFormat
import heycam.core.model.ExportOptions
import heycam.core.model.Folder
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.core.model.PageText
import heycam.core.model.TextStatus
import heycam.feature.document.text.FindMatch
import heycam.feature.document.text.findMatches
import java.text.Normalizer
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class DocumentTab { PAGES, TEXT }

internal data class DocumentUiState(
    val loaded: Boolean = false,
    val document: Document? = null,
    /** Pages in display order, without pages waiting on Undo. */
    val pages: List<Page> = emptyList(),
    val texts: Map<PageId, PageText> = emptyMap(),
    /** Text models still downloading (or failed) while this document has pages to read. */
    val models: ModuleState? = null,
    val sharing: Boolean = false,
) {
    /** The document is gone (deleted, or moved to trash from elsewhere). */
    val missing: Boolean get() = loaded && document == null

    val storedBytes: Long
        get() = pages.sumOf { p -> p.source.bytes + (p.render?.takeIf { it.path != p.source.path }?.bytes ?: 0L) }

    /** The TEXT flag: nothing is still being read and at least one page has text. A photo with no words gets none. */
    val textReady: Boolean
        get() = pages.isNotEmpty() && pages.none { it.textStatus == TextStatus.PENDING } && pages.any { it.textStatus == TextStatus.DONE }

    val pagesReading: Int get() = pages.count { it.textStatus == TextStatus.PENDING }
}

internal data class FindState(val query: String = "", val matches: List<FindMatch> = emptyList(), val current: Int = 0) {
    val currentMatch: FindMatch? get() = matches.getOrNull(current)
}

internal sealed interface DocumentMessage {
    data class PageDeleted(val pageId: PageId) : DocumentMessage
    data class Moved(val folderName: String?) : DocumentMessage
    data object TextSaved : DocumentMessage
    data object Copied : DocumentMessage
    data class ExportFailed(val reason: ExportFailure) : DocumentMessage
    data object Failed : DocumentMessage
}

internal sealed interface DocumentEvent {
    data class Message(val message: DocumentMessage) : DocumentEvent
    data class Share(val chooser: Intent) : DocumentEvent
    /** Some pages are still being read: ask "Wait" or "Share without them". */
    data class AskTextPending(val pages: Int) : DocumentEvent
}

/**
 * Document (S8, S10, F6, F7): the page grid with reorder, rotate and delete; the recognised text with find,
 * corrections, copy and share; and the document's own rename, pin, move and trash.
 */
@HiltViewModel
internal class DocumentViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    private val processor: PageProcessor,
    private val workStore: PageWorkStore,
    private val textEditor: PageTextEditor,
    private val export: ExportService,
    private val deletions: PageDeletions,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val route = savedState.toRoute<DocumentRoute>()
    val documentId = DocumentId(route.documentId)

    /** Page to bring into view when the screen first shows (from search, or back from the viewer). */
    val initialPage: Int? = route.pageIndex

    val tab: StateFlow<DocumentTab> =
        savedState.getStateFlow(KEY_TAB, if (route.showText) DocumentTab.TEXT else DocumentTab.PAGES)

    private val query = savedState.getStateFlow(KEY_QUERY, route.highlight.orEmpty())
    /** Null until the user steps through matches: then the first match on or after [initialPage] is current. */
    private val findIndex = savedState.getStateFlow<Int?>(KEY_FIND_INDEX, null)

    /** Optimistic order after a drag, until the repository reports it. */
    private val order = MutableStateFlow<List<PageId>?>(null)
    private val sharing = MutableStateFlow(false)

    private val _events = Channel<DocumentEvent>(Channel.BUFFERED)
    val events: Flow<DocumentEvent> = _events.receiveAsFlow()

    val state: StateFlow<DocumentUiState> = combine(
        documents.observeDocument(documentId),
        documents.observeText(documentId),
        deletions.pending,
        order,
        combine(processor.state, sharing) { p, s -> p to s },
    ) { doc, texts, pending, override, (processing, isSharing) ->
        val live = doc?.takeIf { it.trashedAt == null }
        val visible = live?.pages.orEmpty().filter { it.id !in pending }
        val pages = override?.let { ids -> reorder(visible, ids) } ?: visible
        DocumentUiState(
            loaded = true,
            document = live,
            pages = pages,
            texts = texts.associateBy { it.pageId },
            models = processing.models?.takeIf { pages.any { it.textStatus == TextStatus.PENDING } },
            sharing = isSharing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentUiState())

    val find: StateFlow<FindState> = combine(
        state.map { s -> s.pages.map { it.id to s.texts[it.id]?.text } }.distinctUntilChanged(),
        query,
        findIndex,
    ) { pages, q, index ->
        val matches = findMatches(pages, q)
        val current = when {
            matches.isEmpty() -> 0
            index != null -> index.coerceIn(0, matches.lastIndex)
            else -> matches.indexOfFirst { it.pageIndex >= (initialPage ?: 0) }.takeIf { it >= 0 } ?: 0
        }
        FindState(q, matches, current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FindState(route.highlight.orEmpty()))

    init {
        viewModelScope.launch { deletions.failed.collect { message(DocumentMessage.Failed) } }
    }

    fun setTab(tab: DocumentTab) { savedState[KEY_TAB] = tab }

    // ---- find ----

    fun setQuery(text: String) {
        savedState[KEY_QUERY] = text
        savedState[KEY_FIND_INDEX] = 0
    }

    fun nextMatch() = stepMatch(+1)

    fun previousMatch() = stepMatch(-1)

    private fun stepMatch(step: Int) {
        val f = find.value
        if (f.matches.isEmpty()) return
        savedState[KEY_FIND_INDEX] = Math.floorMod(f.current + step, f.matches.size)
    }

    // ---- document ----

    fun rename(title: String) {
        val clean = title.trim()
        if (clean.isEmpty() || clean == state.value.document?.title) return
        launchSafely { documents.rename(documentId, clean) }
    }

    fun togglePinned() {
        val doc = state.value.document ?: return
        launchSafely { documents.setPinned(documentId, !doc.pinned) }
    }

    fun moveTo(folder: Folder?) = launchSafely {
        documents.moveToFolder(listOf(documentId), folder?.id)
        message(DocumentMessage.Moved(folder?.name))
    }

    suspend fun folderTree(): List<FolderNode> = folders.tree()

    /** Moves the document to trash; the screen closes when the repository reports it. */
    fun trash() = launchSafely { documents.trash(listOf(documentId)) }

    // ---- pages ----

    /** Drag or TalkBack move, in display positions. */
    fun movePage(from: Int, to: Int) {
        val visible = state.value.pages
        if (from !in visible.indices || to !in visible.indices || from == to) return
        val reordered = visible.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
        order.value = reordered
        viewModelScope.launch {
            try {
                documents.movePage(documentId, visible[from].index, visible[to].index)
                withTimeoutOrNull(SETTLE_MS) {
                    documents.observeDocument(documentId).first { doc ->
                        doc == null || doc.pages.map { it.id }.filter { it in reordered } == reordered
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(DocumentMessage.Failed)
            } finally {
                if (order.value == reordered) order.value = null
            }
        }
    }

    fun rotate(pageId: PageId) = edit(listOf(pageId)) { it.copy(rotation = it.rotation.clockwise()) }

    fun setFilter(pageIds: List<PageId>, filter: PageFilter) = edit(pageIds) { it.copy(filter = filter) }

    /**
     * Hides the page and deletes it after the Undo window ([windowMs], longer when accessibility settings ask for it);
     * the only page of a document is never deleted here.
     */
    fun deletePage(pageId: PageId, windowMs: Long = PageDeletions.UNDO_WINDOW_MS) {
        if (state.value.pages.size <= 1) return
        deletions.delete(pageId, windowMs)
        message(DocumentMessage.PageDeleted(pageId))
    }

    fun undoDelete(pageId: PageId) { deletions.undo(pageId) }

    // ---- text ----

    /** "Read again as …" for every page; null = Auto. */
    fun readAgain(script: OcrScript?) = launchSafely { processor.reread(documentId, script) }

    /** Queues one page whose reading failed (or was skipped) for another try. */
    fun retryText(pageId: PageId) {
        val page = state.value.pages.firstOrNull { it.id == pageId } ?: return
        launchSafely {
            workStore.setTextStatus(pageId, page.editsVersion, TextStatus.PENDING)
            processor.retry() // a text model that kept failing gets fresh download attempts too
        }
    }

    /**
     * Saves a correction to a page's recognised text (search and TXT export use it; the PDF layer keeps the line
     * geometry). It applies whatever has happened to the page since it was read, such as a new filter.
     */
    fun saveText(pageId: PageId, text: String) {
        val current = state.value.texts[pageId] ?: return
        val clean = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        if (clean == current.text) return
        launchSafely {
            textEditor.updateText(pageId, clean)
            message(DocumentMessage.TextSaved)
        }
    }

    /** Shares the recognised text as a .txt file through the share sheet. */
    fun shareText(policy: TextPendingPolicy = TextPendingPolicy.ASK) {
        if (sharing.value) return
        sharing.value = true
        // Waiting needs something reading the pages.
        if (policy == TextPendingPolicy.WAIT) processor.kick()
        viewModelScope.launch {
            try {
                val request = ExportRequest(listOf(documentId), ExportOptions(format = ExportFormat.TEXT), policy)
                when (val result = export.export(request, ExportTarget.Share)) {
                    is ExportResult.ReadyToShare -> _events.send(DocumentEvent.Share(result.chooser))
                    is ExportResult.TextPending -> _events.send(DocumentEvent.AskTextPending(result.pagesPending))
                    is ExportResult.Failed ->
                        if (result.reason != ExportFailure.CANCELLED) message(DocumentMessage.ExportFailed(result.reason))
                    is ExportResult.Saved -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(DocumentMessage.Failed)
            } finally {
                sharing.value = false
            }
        }
    }

    /** Before Android 13 the system does not confirm a copy, so the screen says it. */
    fun notifyCopied() = message(DocumentMessage.Copied)

    /** Text models failed to download: try again now, with fresh download attempts. */
    fun retryModels() = processor.retry()

    private fun edit(pageIds: List<PageId>, transform: (PageEdits) -> PageEdits) {
        if (pageIds.isEmpty()) return
        launchSafely {
            documents.updateEdits(pageIds, transform)
            processor.kick()
        }
    }

    private fun message(message: DocumentMessage) {
        _events.trySend(DocumentEvent.Message(message))
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(DocumentMessage.Failed)
            }
        }
    }

    private companion object {
        const val KEY_TAB = "tab"
        const val KEY_QUERY = "find"
        const val KEY_FIND_INDEX = "findIndex"
        const val SETTLE_MS = 2_000L

        /** [ids] as an order over [pages]; null once the two no longer hold the same pages. */
        fun reorder(pages: List<Page>, ids: List<PageId>): List<Page>? {
            val byId = pages.associateBy { it.id }
            if (byId.size != ids.size || !ids.all { it in byId }) return null
            return ids.map { byId.getValue(it) }
        }
    }
}
