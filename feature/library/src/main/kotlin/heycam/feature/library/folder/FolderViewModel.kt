package heycam.feature.library.folder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.DocumentRepository
import heycam.core.data.FolderRepository
import heycam.core.domain.PageProcessor
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.FolderId
import heycam.core.model.SortOrder
import heycam.feature.library.FolderRoute
import heycam.feature.library.contents.ContentsViewModel
import heycam.feature.library.contents.MAX_FOLDER_DEPTH
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

internal data class FolderUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val folder: Folder? = null,
    val path: List<Folder> = emptyList(),
    val subfolders: List<Folder> = emptyList(),
    val documents: List<DocumentSummary> = emptyList(),
    val sort: SortOrder = SortOrder.UPDATED_DESC,
    val selection: Set<DocumentId> = emptySet(),
) {
    /** Top-level folders are depth 1. */
    val depth: Int get() = path.size + 1
    val canNest: Boolean get() = depth < MAX_FOLDER_DEPTH
    val selected: List<DocumentSummary> get() = documents.filter { it.id in selection }
}

/** Folder view (S4): its sub-folders, then its documents with pinned ones first. */
@HiltViewModel
internal class FolderViewModel @Inject constructor(
    documents: DocumentRepository,
    folders: FolderRepository,
    processor: PageProcessor,
    private val savedState: SavedStateHandle,
) : ContentsViewModel(documents, folders, processor) {

    val folderId = FolderId(savedState.toRoute<FolderRoute>().folderId)
    private val sort = savedState.getStateFlow(KEY_SORT, SortOrder.UPDATED_DESC)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<FolderUiState> = combine(
        sort.flatMapLatest { documents.observeLibrary(folderId, it) },
        sort,
        selection,
    ) { snapshot, order, _ ->
        val folder = snapshot.folder
        if (folder == null) {
            FolderUiState(loaded = true, missing = true, sort = order)
        } else {
            val docs = snapshot.documents.sortedByDescending { it.pinned }
            FolderUiState(
                loaded = true,
                folder = folder,
                path = snapshot.breadcrumbs.filter { it.id != folder.id },
                subfolders = snapshot.folders,
                documents = docs,
                sort = order,
                selection = pruneSelection(docs),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState())

    fun setSort(order: SortOrder) { savedState[KEY_SORT] = order }

    fun renameFolder(name: String) = renameFolder(folderId, state.value.folder?.parentId, name)

    /** Names already used next to this folder, for the "Rename folder" dialog. */
    suspend fun siblingNames(): Set<String> = folderNames(state.value.folder?.parentId, except = folderId)

    /** Names already used inside this folder, for the "New folder" dialog. */
    suspend fun subfolderNames(): Set<String> = folderNames(folderId)

    /** Documents inside move up to the parent folder (or Contents); sub-folders move up a level. */
    fun deleteFolder() {
        val parent = state.value.folder?.parentId
        launchSafely { folders.delete(folderId, moveDocumentsTo = parent) }
    }

    fun createSubfolder(name: String) = createFolder(name, folderId, state.value.depth)

    private companion object {
        const val KEY_SORT = "sort"
    }
}
