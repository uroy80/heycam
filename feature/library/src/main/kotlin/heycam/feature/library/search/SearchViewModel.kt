package heycam.feature.library.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.core.data.DocumentRepository
import heycam.core.model.SearchHit
import heycam.core.model.SearchQuery
import heycam.feature.library.SearchRoute
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal sealed interface SearchResults {
    /** Nothing typed yet. */
    data object Idle : SearchResults
    data class Searching(val query: String) : SearchResults
    data class Done(val query: String, val titles: List<SearchHit.TitleHit>, val inText: List<SearchHit.TextHit>) : SearchResults {
        val isEmpty: Boolean get() = titles.isEmpty() && inText.isEmpty()
    }
    data object Failed : SearchResults
}

/** Search (S3, F5): live, debounced 200 ms, titles first and then hits inside recognised text. */
@HiltViewModel
internal class SearchViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** The field's text; seeded from the route's initial query, kept across process death. */
    val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, savedState.toRoute<SearchRoute>().initialQuery.orEmpty())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }
        .flatMapLatest { q ->
            flow {
                if (q.isEmpty()) {
                    emit(SearchResults.Idle)
                    return@flow
                }
                emit(SearchResults.Searching(q))
                val hits = try {
                    documents.search(SearchQuery(text = q))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(SearchResults.Failed)
                    return@flow
                }
                emit(
                    SearchResults.Done(
                        query = q,
                        titles = hits.filterIsInstance<SearchHit.TitleHit>(),
                        inText = hits.filterIsInstance<SearchHit.TextHit>(),
                    ),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.Idle)

    fun onQueryChange(text: String) { savedState[KEY_QUERY] = text }

    private companion object {
        const val KEY_QUERY = "query"
        const val DEBOUNCE_MS = 200L
    }
}
