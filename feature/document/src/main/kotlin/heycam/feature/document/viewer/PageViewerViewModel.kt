package heycam.feature.document.viewer

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.DocumentRepository
import heycam.core.domain.PageProcessor
import heycam.core.imaging.PageRenderer
import heycam.core.model.Document
import heycam.core.model.DocumentId
import heycam.core.model.Page
import heycam.core.model.PageEdits
import heycam.core.model.PageFilter
import heycam.core.model.PageId
import heycam.feature.document.DocumentMessage
import heycam.feature.document.PageDeletions
import heycam.feature.document.PageViewerRoute
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class ViewerUiState(
    val loaded: Boolean = false,
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val current: Int = 0,
) {
    val missing: Boolean get() = loaded && (document == null || pages.isEmpty())
    val currentPage: Page? get() = pages.getOrNull(current)
    val canDelete: Boolean get() = pages.size > 1
}

/** Page viewer (S9): one page full-bleed; Rotate, Filter, Retake and Delete with Undo. */
@HiltViewModel
internal class PageViewerViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val renderer: PageRenderer,
    private val processor: PageProcessor,
    private val deletions: PageDeletions,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val route = savedState.toRoute<PageViewerRoute>()
    val documentId = DocumentId(route.documentId)
    private val currentIndex = savedState.getStateFlow(KEY_CURRENT, route.pageIndex)

    private val _messages = Channel<DocumentMessage>(Channel.BUFFERED)
    val messages: Flow<DocumentMessage> = _messages.receiveAsFlow()

    val state: StateFlow<ViewerUiState> = combine(
        documents.observeDocument(documentId),
        deletions.pending,
        currentIndex,
    ) { doc, pending, index ->
        val live = doc?.takeIf { it.trashedAt == null }
        val pages = live?.pages.orEmpty().filter { it.id !in pending }
        ViewerUiState(loaded = true, document = live, pages = pages, current = index.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewerUiState())

    private val previews = object : LruCache<PreviewKey, Bitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: PreviewKey, value: Bitmap) = value.allocationByteCount
    }

    init {
        viewModelScope.launch { deletions.failed.collect { _messages.send(DocumentMessage.Failed) } }
    }

    fun select(index: Int) {
        if (index in state.value.pages.indices) savedState[KEY_CURRENT] = index
    }

    fun rotate() = editCurrent { it.copy(rotation = it.rotation.clockwise()) }

    fun setFilter(filter: PageFilter) = editCurrent { it.copy(filter = filter) }

    fun applyFilterToAll(filter: PageFilter) {
        val ids = state.value.pages.map { it.id }
        edit(ids) { it.copy(filter = filter) }
    }

    /** Where each page waiting on Undo was, so Undo brings the viewer back to it. */
    private val deletedAt = HashMap<PageId, Int>()

    /** Hides the current page and deletes it after [windowMs] unless Undo comes first. */
    fun deleteCurrent(windowMs: Long = PageDeletions.UNDO_WINDOW_MS) {
        val s = state.value
        val page = s.currentPage ?: return
        if (!s.canDelete) return
        deletedAt.keys.retainAll(deletions.pending.value) // pages deleted for good no longer need a place
        deletedAt[page.id] = s.current
        deletions.delete(page.id, windowMs)
        _messages.trySend(DocumentMessage.PageDeleted(page.id))
    }

    /** Puts the page back and shows it again. */
    fun undoDelete(pageId: PageId) {
        val index = deletedAt.remove(pageId)
        if (deletions.undo(pageId) && index != null) savedState[KEY_CURRENT] = index
    }

    /** A downsampled render of [page] with [edits]; cached, null if it cannot be read. */
    suspend fun preview(page: Page, edits: PageEdits, maxLongEdgePx: Int): Bitmap? {
        val key = PreviewKey(page.source.path, edits, maxLongEdgePx)
        previews.get(key)?.let { return it }
        return try {
            renderer.preview(page.source, edits, maxLongEdgePx).also { previews.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun editCurrent(transform: (PageEdits) -> PageEdits) {
        val page = state.value.currentPage ?: return
        edit(listOf(page.id), transform)
    }

    private fun edit(ids: List<PageId>, transform: (PageEdits) -> PageEdits) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                documents.updateEdits(ids, transform)
                processor.kick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(DocumentMessage.Failed)
            }
        }
    }

    private data class PreviewKey(val path: String, val edits: PageEdits, val maxPx: Int)

    private companion object {
        const val KEY_CURRENT = "current"
        val PREVIEW_CACHE_BYTES = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    }
}
