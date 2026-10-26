package heycam.feature.library.contents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import heycam.core.data.DocumentRepository
import heycam.core.data.FolderNameInUseException
import heycam.core.data.FolderRepository
import heycam.core.domain.PageProcessor
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Short, actionable feedback shown in the snackbar. */
internal sealed interface LibraryMessage {
    data class Trashed(val ids: List<DocumentId>) : LibraryMessage
    data class Moved(val count: Int, val folderName: String?) : LibraryMessage
    data object FolderLimit : LibraryMessage
    /** Another folder in the same place already has that name (letter case aside). */
    data object NameInUse : LibraryMessage
    data object Failed : LibraryMessage
}

/** A folder in the "Move to" picker; depth 0 = top level. */
internal data class FolderNode(val folder: Folder, val depth: Int)

/** Folders nest at most three deep (PLAN §2 item 21). */
internal const val MAX_FOLDER_DEPTH = 3

/** How folder names are compared for duplicates: trimmed, NFC and lower-cased, so "Receipts" and "receipts" clash. */
internal fun folderNameKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase()

/** Selection, pin, rename, move and trash shared by Home and the folder view. */
internal abstract class ContentsViewModel(
    protected val documents: DocumentRepository,
    protected val folders: FolderRepository,
    private val processor: PageProcessor,
) : ViewModel() {

    private val _selection = MutableStateFlow<Set<DocumentId>>(emptySet())
    val selection: StateFlow<Set<DocumentId>> = _selection.asStateFlow()

    private val _messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val messages: Flow<LibraryMessage> = _messages.receiveAsFlow()

    /** Long-press starts selection; while selecting, a tap toggles. */
    fun toggleSelection(id: DocumentId) = _selection.update { if (id in it) it - id else it + id }

    fun clearSelection() { _selection.value = emptySet() }

    /** Drops ids that are no longer on screen (trashed or moved elsewhere). */
    protected fun pruneSelection(visible: Collection<DocumentSummary>): Set<DocumentId> {
        val ids = visible.mapTo(HashSet()) { it.id }
        val pruned = _selection.value.filterTo(LinkedHashSet()) { it in ids }
        if (pruned.size != _selection.value.size) _selection.value = pruned
        return pruned
    }

    /** Pins everything selected unless all of it is already pinned, in which case it unpins. */
    fun togglePinned(selected: List<DocumentSummary>) {
        if (selected.isEmpty()) return
        val pin = !selected.all { it.pinned }
        launchSafely {
            selected.forEach { documents.setPinned(it.id, pin) }
            clearSelection()
        }
    }

    fun rename(id: DocumentId, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        launchSafely { documents.rename(id, clean) }
    }

    fun moveTo(ids: List<DocumentId>, folder: Folder?) {
        if (ids.isEmpty()) return
        launchSafely {
            documents.moveToFolder(ids, folder?.id)
            clearSelection()
            _messages.send(LibraryMessage.Moved(ids.size, folder?.name))
        }
    }

    fun trash(ids: List<DocumentId>) {
        if (ids.isEmpty()) return
        launchSafely {
            documents.trash(ids)
            clearSelection()
            _messages.send(LibraryMessage.Trashed(ids))
        }
    }

    /** Undo of "Moved to trash". Trashed pages are skipped by the page processor, so wake it for any unfinished work. */
    fun undoTrash(ids: List<DocumentId>) = launchSafely {
        documents.restore(ids)
        processor.kick()
    }

    /** Creates a folder under [parent] (null = top level), refusing anything deeper than three levels or a taken name. */
    fun createFolder(name: String, parent: FolderId?, parentDepth: Int) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        launchSafely {
            when {
                parentDepth >= MAX_FOLDER_DEPTH -> _messages.send(LibraryMessage.FolderLimit)
                folderNameKey(clean) in folderNames(parent) -> _messages.send(LibraryMessage.NameInUse)
                else -> folders.create(clean, parent)
            }
        }
    }

    /** Renames [id] (whose parent is [parent]) unless another folder there already has that name. */
    protected fun renameFolder(id: FolderId, parent: FolderId?, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        launchSafely {
            if (folderNameKey(clean) in folderNames(parent, except = id)) _messages.send(LibraryMessage.NameInUse)
            else folders.rename(id, clean)
        }
    }

    /** The names (as [folderNameKey]) of the folders under [parent], leaving out [except]. */
    suspend fun folderNames(parent: FolderId?, except: FolderId? = null): Set<String> =
        folders.observeFolders(parent).first().filter { it.id != except }.mapTo(HashSet()) { folderNameKey(it.name) }

    /** Every folder, depth-first and sorted by name, for the "Move to" picker. */
    suspend fun folderTree(): List<FolderNode> {
        val out = ArrayList<FolderNode>()
        suspend fun walk(parent: FolderId?, depth: Int) {
            if (depth >= MAX_FOLDER_DEPTH) return
            for (f in folders.observeFolders(parent).first().sortedBy { it.name.lowercase() }) {
                out += FolderNode(f, depth)
                walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    protected fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FolderNameInUseException) {
                _messages.send(LibraryMessage.NameInUse)   // taken between the dialog's check and the save
            } catch (e: Exception) {
                _messages.send(LibraryMessage.Failed)
            }
        }
    }
}
