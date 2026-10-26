package heycam.feature.library.search

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import heycam.core.model.PageId
import heycam.core.model.SearchHit
import heycam.core.model.Snippet
import heycam.feature.library.FakeLibrary
import heycam.feature.library.MainDispatcherRule
import heycam.feature.library.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val library = FakeLibrary(docs = listOf(summary(1, "Lease agreement"), summary(2, "Bank letter")))

    private fun TestScope.viewModel(initial: String? = null): SearchViewModel {
        val handle = SavedStateHandle(if (initial == null) emptyMap() else mapOf("initialQuery" to initial))
        val vm = SearchViewModel(library, handle)
        backgroundScope.launch { vm.results.collect {} }
        return vm
    }

    @Test
    fun waitsForTypingToPauseThenSearches() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel()
        runCurrent()
        assertThat(vm.results.value).isEqualTo(SearchResults.Idle)
        vm.onQueryChange("lea")
        advanceTimeBy(100)
        vm.onQueryChange("leas")
        advanceTimeBy(199)
        assertThat(library.searches).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(library.searches.map { it.text }).containsExactly("leas")
        val done = vm.results.value as SearchResults.Done
        assertThat(done.titles.single().document.title).isEqualTo("Lease agreement")
        assertThat(done.inText).isEmpty()
    }

    @Test
    fun splitsTitlesAndTextHits() = runTest(main.dispatcher.scheduler) {
        val lease = library.docs.value[0]
        library.searchHits = listOf(
            SearchHit.TitleHit(lease, 0 until 5),
            SearchHit.TextHit(lease, PageId(10), 2, Snippet("…pay a deposit of four weeks…", listOf(6 until 13))),
        )
        val vm = viewModel(initial = "deposit")
        advanceTimeBy(250)
        runCurrent()
        assertThat(vm.query.value).isEqualTo("deposit")
        val done = vm.results.value as SearchResults.Done
        assertThat(done.query).isEqualTo("deposit")
        assertThat(done.titles).hasSize(1)
        assertThat(done.inText.single().pageIndex).isEqualTo(2)
    }

    @Test
    fun clearingGoesIdleAtOnce() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel(initial = "bank")
        advanceTimeBy(250)
        runCurrent()
        vm.onQueryChange("   ")
        runCurrent()
        assertThat(vm.results.value).isEqualTo(SearchResults.Idle)
    }

    @Test
    fun noMatchesAndFailures() = runTest(main.dispatcher.scheduler) {
        val vm = viewModel(initial = "zzz")
        advanceTimeBy(250)
        runCurrent()
        assertThat((vm.results.value as SearchResults.Done).isEmpty).isTrue()
        library.failSearch = true
        vm.onQueryChange("bank")
        advanceTimeBy(250)
        runCurrent()
        assertThat(vm.results.value).isEqualTo(SearchResults.Failed)
    }
}
