package heycam.feature.library.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.DocumentRepository
import heycam.core.domain.PageProcessor
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TrashUiState(
    val loaded: Boolean = false,
    val documents: List<DocumentSummary> = emptyList(),
    val selection: Set<DocumentId> = emptySet(),
)

internal sealed interface TrashMessage {
    data class Restored(val count: Int) : TrashMessage
    data class Deleted(val count: Int) : TrashMessage
    data object Failed : TrashMessage
}

/** Recycle bin (S5): restore or delete for good. Opening a trashed document is not offered. */
@HiltViewModel
internal class TrashViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val processor: PageProcessor,
) : ViewModel() {

    private val selection = MutableStateFlow<Set<DocumentId>>(emptySet())
    private val _messages = Channel<TrashMessage>(Channel.BUFFERED)
    val messages: Flow<TrashMessage> = _messages.receiveAsFlow()

    val state: StateFlow<TrashUiState> = combine(documents.observeTrash(), selection) { docs, selected ->
        val ids = docs.mapTo(HashSet()) { it.id }
        TrashUiState(loaded = true, documents = docs, selection = selected.filterTo(LinkedHashSet()) { it in ids })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun toggleSelection(id: DocumentId) = selection.update { if (id in it) it - id else it + id }

    fun clearSelection() { selection.value = emptySet() }

    /** Brings documents back; the page processor skips trashed ones, so it is woken for any unfinished pages. */
    fun restore(ids: List<DocumentId>) = run(ids) {
        documents.restore(ids)
        processor.kick()
        TrashMessage.Restored(ids.size)
    }

    fun deleteForever(ids: List<DocumentId>) = run(ids) {
        documents.deleteForever(ids)
        TrashMessage.Deleted(ids.size)
    }

    fun emptyTrash() = deleteForever(state.value.documents.map { it.id })

    private fun run(ids: List<DocumentId>, block: suspend () -> TrashMessage) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val message = try {
                block().also { clearSelection() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrashMessage.Failed
            }
            _messages.send(message)
        }
    }
}
