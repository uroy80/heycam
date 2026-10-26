package heycam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.common.HeycamLog
import heycam.core.data.DocumentRepository
import heycam.core.data.FileStore
import heycam.core.data.StorageUsage
import heycam.core.model.DocumentSummary
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUiState(
    /** Null until measured, or when it could not be measured. */
    val usage: StorageUsage? = null,
    val trashCount: Int = 0,
    /** True while trash is emptied or temporary files are cleared. */
    val busy: Boolean = false,
)

sealed interface StorageMessage {
    data class Freed(val bytes: Long) : StorageMessage
    data object TrashEmptied : StorageMessage
    data object Failed : StorageMessage
}

/** Storage (S14): what heycam keeps on the phone, emptying the trash and clearing temporary files. */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val fileStore: FileStore,
    private val documents: DocumentRepository,
    private val log: HeycamLog,
) : ViewModel() {

    private val usage = MutableStateFlow<StorageUsage?>(null)
    private val busy = MutableStateFlow(false)

    private val trash: StateFlow<List<DocumentSummary>> = documents.observeTrash()
        .catch { e ->
            log.w(TAG, "trash unavailable: ${e.javaClass.simpleName}")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = Channel<StorageMessage>(Channel.BUFFERED)
    val messages: Flow<StorageMessage> = _messages.receiveAsFlow()

    val uiState: StateFlow<StorageUiState> = combine(usage, trash, busy) { u, t, b ->
        StorageUiState(usage = u, trashCount = t.size, busy = b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StorageUiState())

    fun refresh() {
        viewModelScope.launch { measure() }
    }

    fun emptyTrash() = work {
        val ids = trash.value.map { it.id }
        if (ids.isNotEmpty()) documents.deleteForever(ids)
        StorageMessage.TrashEmptied
    }

    fun clearTemporaryFiles() = work { StorageMessage.Freed(fileStore.clearTemporaryFiles().bytesFreed) }

    private fun work(block: suspend () -> StorageMessage) {
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "storage action failed: ${e.javaClass.simpleName}")
                StorageMessage.Failed
            }
            measure()
            busy.update { false }
            _messages.send(message)
        }
    }

    private suspend fun measure() {
        usage.value = try {
            fileStore.usage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "usage unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "Storage"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
