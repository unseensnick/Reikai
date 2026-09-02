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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.fillEntryRows
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO

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
    /** The content type this search is scoped to, or null to open on the Browse chip. */
    @Assisted private val scopedContentType: ContentType?,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val mihonSourcePreferences: SourcePreferences,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State(query = initialQuery))

    /** Serialises the decide-and-launch below; see [search]. */
    private val searchGuard = Any()
    private var searchJob: Job? = null
    private var lastQuery: String? = null
    private var lastFilter: SearchSourceFilter? = null
    private var lastContentType: ContentType? = null

    init {
        viewModelScope.launchIO {
            // Read once rather than followed: nothing else can move the Browse chip while this
            // screen is up, and a search opened from a manga or a novel is scoped to that instead.
            state.update {
                it.copy(contentType = scopedContentType ?: sourcePreferences.browseContentType.get())
            }
            search(state.value.query)
        }
        viewModelScope.launchIO {
            mihonSourcePreferences.globalSearchFilterState.changes().collectLatest { onlyHasResults ->
                state.update { it.copy(onlyShowHasResults = onlyHasResults) }
            }
        }
    }

    fun setContentType(contentType: ContentType) {
        // A scoped search keeps its choice to itself: it was opened from an entry of a known type,
        // so switching tabs here must not reprogram what the Browse tab opens on.
        if (scopedContentType == null) sourcePreferences.browseContentType.set(contentType)
        state.update { it.copy(contentType = contentType) }
        // The tab changes which sources are in scope, so the current query is re-run over the new
        // set rather than left showing the old one's rows.
        search(state.value.query)
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

    // Guarded because the tab strip and the toolbar call this from the UI while the opening search
    // runs on a background thread, and deciding whether to re-run is a read followed by a write.
    fun search(query: String) = synchronized(searchGuard) {
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
        // Results already in hand for this same query are kept rather than re-fetched, so widening
        // Pinned to All only searches what was added. Errors are dropped, so they do get retried.
        val reusable = if (query == lastQuery) {
            state.value.rows.filter { it.state is EntrySearchState.Success }.associateBy { it.key }
        } else {
            emptyMap()
        }
        lastQuery = query
        lastFilter = filter
        lastContentType = contentType

        searchJob?.cancel()
        searchJob = viewModelScope.launchIO {
            val active = providers.filter { it.shows(contentType) }
            val rows = active.flatMap { it.sources(filter) }
                .map { row -> reusable[row.key]?.let { row.copy(state = it.state) } ?: row }
                .sortedWith(searchRowComparator)
            // `searched` distinguishes "no source matched" from "the search has not started", which
            // a screen waiting on the first result cannot otherwise tell apart.
            state.update { it.copy(query = query, rows = rows, searched = true) }

            // Grouped by content type, so a slow half never starves the other of its slots.
            fillEntryRows(
                rows = rows,
                group = { it.key.contentType },
                order = searchRowComparator,
                updateRows = { transform -> state.update { it.copy(rows = transform(it.rows)) } },
                load = { row ->
                    active.first { it.contentType == row.key.contentType }.search(row, query)
                },
            )
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
        /** Whether a search has been dispatched, so an empty [rows] reads as "no source matched"
         *  rather than "not started yet". */
        val searched: Boolean = false,
    ) {
        val progress: Int get() = rows.count { it.state !is EntrySearchState.Loading }
        val total: Int get() = rows.size

        // Computed once per state rather than on each read: the list is read several times per pass.
        val visibleRows: List<BrowseSearchRow> =
            if (onlyShowHasResults) rows.filter { it.hasResults() } else rows
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            providers: List<GlobalSearchProvider>,
            initialQuery: String,
            scopedContentType: ContentType?,
        ): GlobalSearchEngine
    }
}
