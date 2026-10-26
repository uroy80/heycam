package heycam.feature.library.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.DocumentRepository
import heycam.core.data.FolderRepository
import heycam.core.domain.PageProcessor
import heycam.core.model.DocumentId
import heycam.core.model.DocumentSummary
import heycam.core.model.Folder
import heycam.core.model.LibrarySnapshot
import heycam.core.model.LibraryStats
import heycam.core.model.SortOrder
import heycam.feature.library.contents.ContentsViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A top-level folder on Home with a preview of its first documents. */
internal data class FolderSection(val folder: Folder, val preview: List<DocumentSummary>)

internal data class HomeUiState(
    val loaded: Boolean = false,
    val stats: LibraryStats = LibraryStats(0, 0, 0),
    val latest: DocumentSummary? = null,
    val latestFolder: String? = null,
    val pinned: List<DocumentSummary> = emptyList(),
    val sections: List<FolderSection> = emptyList(),
    val documents: List<DocumentSummary> = emptyList(),
    val sort: SortOrder = SortOrder.UPDATED_DESC,
    val selection: Set<DocumentId> = emptySet(),
) {
    val isEmpty: Boolean get() = loaded && latest == null && pinned.isEmpty() && sections.isEmpty() && documents.isEmpty()
    val visible: List<DocumentSummary> get() = listOfNotNull(latest) + pinned + sections.flatMap { it.preview } + documents
    val selected: List<DocumentSummary> get() = visible.filter { it.id in selection }
}

/** Home / Contents (S1, S2, F4): the latest scan as the lead, then pinned, folders as sections, then loose documents. */
@HiltViewModel
internal class HomeViewModel @Inject constructor(
    documents: DocumentRepository,
    folders: FolderRepository,
    processor: PageProcessor,
    private val savedState: SavedStateHandle,
) : ContentsViewModel(documents, folders, processor) {

    private val sort = savedState.getStateFlow(KEY_SORT, SortOrder.UPDATED_DESC)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val contents: Flow<Pair<LibrarySnapshot, List<FolderSection>>> = sort.flatMapLatest { order ->
        documents.observeLibrary(null, order).flatMapLatest { root ->
            val sections = root.folders.map { folder ->
                documents.observeLibrary(folder.id, order).map { FolderSection(folder, it.documents) }
            }
            if (sections.isEmpty()) flowOf(root to emptyList()) else combine(sections) { root to it.toList() }
        }
    }

    val state: StateFlow<HomeUiState> = combine(
        contents,
        documents.observeRecent(limit = 1),
        documents.observeStats(),
        sort,
        selection,
    ) { (root, sections), recent, stats, order, _ ->
        val latest = recent.firstOrNull()
        fun keep(d: DocumentSummary) = d.id != latest?.id && !d.pinned
        val pinned = (root.documents + sections.flatMap { it.preview })
            .filter { it.pinned && it.id != latest?.id }
            .distinctBy { it.id }
        val draft = HomeUiState(
            loaded = true,
            stats = stats,
            latest = latest,
            latestFolder = latest?.folderId?.let { id -> root.folders.firstOrNull { it.id == id }?.name },
            pinned = pinned,
            sections = sections.map { s -> s.copy(preview = s.preview.filter(::keep).take(SECTION_PREVIEW)) },
            documents = root.documents.filter(::keep),
            sort = order,
        )
        draft.copy(selection = pruneSelection(draft.visible))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setSort(order: SortOrder) { savedState[KEY_SORT] = order }

    private companion object {
        const val KEY_SORT = "sort"
        const val SECTION_PREVIEW = 3
    }
}
