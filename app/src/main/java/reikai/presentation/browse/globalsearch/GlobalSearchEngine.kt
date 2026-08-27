package reikai.presentation.browse.globalsearch

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO

/** How many sources are searched at once, across both content types. */
private const val SEARCH_CONCURRENCY = 5

/**
 * Runs one global search across both content types.
 *
 * All is the real list and the chip is a predicate over it, so everything describing the search is
 * one value here rather than one per type: the query, which sources it covers, the order results
 * land in, how many run at once, and whether the same query is worth running again. A provider only
 * answers about its own sources.
 */
@AssistedInject
class GlobalSearchEngine(
    // Assisted: each provider wraps a ViewModel the screen has already resolved.
    @Assisted private val providers: List<GlobalSearchProvider>,
    @Assisted initialQuery: String,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val mihonSourcePreferences: SourcePreferences,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State(query = initialQuery))

    private var searchJob: Job? = null
    private var lastQuery: String? = null
    private var lastFilter: SearchSourceFilter? = null
    private var lastContentType: ContentType? = null

    init {
        viewModelScope.launchIO {
            sourcePreferences.browseContentType.changes().collectLatest { contentType ->
                state.update { it.copy(contentType = contentType) }
                // The chip changes which sources are in scope, so the current query is re-run over
                // the new set rather than left showing the old one's rows.
                search(state.value.query)
            }
        }
        viewModelScope.launchIO {
            mihonSourcePreferences.globalSearchFilterState.changes().collectLatest { onlyHasResults ->
                state.update { it.copy(onlyShowHasResults = onlyHasResults) }
            }
        }
    }

    fun setContentType(contentType: ContentType) {
        sourcePreferences.browseContentType.set(contentType)
    }

    fun updateQuery(query: String?) {
        state.update { it.copy(query = query.orEmpty()) }
    }

    fun setSourceFilter(filter: SearchSourceFilter) {
        state.update { it.copy(sourceFilter = filter) }
        search(state.value.query)
    }

    /** Hide sources that returned nothing. One toggle, one preference, for both content types. */
    fun toggleHasResults() {
        mihonSourcePreferences.globalSearchFilterState.toggle()
    }

    fun search(query: String) {
        val filter = state.value.sourceFilter
        val contentType = state.value.contentType
        if (query.isBlank()) {
            searchJob?.cancel()
            state.update { it.copy(query = "", rows = emptyList()) }
            lastQuery = null
            return
        }
        // Nothing about the search changed, so re-running it would only throw away results the user
        // is already looking at.
        if (query == lastQuery && filter == lastFilter && contentType == lastContentType) return
        lastQuery = query
        lastFilter = filter
        lastContentType = contentType

        searchJob?.cancel()
        searchJob = viewModelScope.launchIO {
            val active = providers.filter { it.shows(contentType) }
            val rows = active.flatMap { it.sources(filter) }.sortedWith(searchRowComparator)
            state.update { it.copy(query = query, rows = rows) }

            val semaphore = Semaphore(SEARCH_CONCURRENCY)
            rows.map { row ->
                async {
                    val provider = active.first { it.contentType == row.key.contentType }
                    val result = semaphore.withPermit {
                        try {
                            EntrySearchState.Success(provider.search(row, query))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            EntrySearchState.Error(e.message)
                        }
                    }
                    // Read and write in one update: sources finish within milliseconds of each other,
                    // and reading outside would let two of them each write their own result onto the
                    // same snapshot, leaving whichever lost still spinning.
                    state.update { current ->
                        current.copy(
                            rows = current.rows
                                .map { if (it.key == row.key) it.copy(state = result) else it }
                                .sortedWith(searchRowComparator),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun GlobalSearchProvider.shows(contentType: ContentType) =
        contentType == ContentType.ALL || contentType == this.contentType

    @Immutable
    data class State(
        val query: String = "",
        val contentType: ContentType = ContentType.ALL,
        val sourceFilter: SearchSourceFilter = SearchSourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val rows: List<BrowseSearchRow> = emptyList(),
    ) {
        val progress: Int get() = rows.count { it.state !is EntrySearchState.Loading }
        val total: Int get() = rows.size
        val visibleRows: List<BrowseSearchRow> get() =
            if (onlyShowHasResults) rows.filter { it.hasResults() } else rows
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(providers: List<GlobalSearchProvider>, initialQuery: String): GlobalSearchEngine
    }
}
