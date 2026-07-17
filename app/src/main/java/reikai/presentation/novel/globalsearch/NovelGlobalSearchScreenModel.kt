package reikai.presentation.novel.globalsearch

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.novel.NovelRepository
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.injectLazy

/** Max sources searched concurrently, matching the manga global search's throttle. */
private const val SEARCH_CONCURRENCY = 5

/**
 * Cross-source light-novel search. Fans [NovelSource.searchNovels] out across every installed source
 * under a [Semaphore], updating each source's row independently as it completes so results fill in
 * progressively (mirrors Mihon's `SearchScreenModel`).
 */
class NovelGlobalSearchScreenModel(
    initialQuery: String,
) : StateScreenModel<NovelGlobalSearchState>(NovelGlobalSearchState(query = initialQuery)) {

    private val installer: LnPluginInstaller by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val libraryAdder: NovelLibraryAdder by injectLazy()
    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()
    private val getEnabledNovelSources: GetEnabledNovelSources by injectLazy()

    private var searchJob: Job? = null

    init {
        mutableState.update { it.copy(onlyShowHasResults = sourcePreferences.novelGlobalSearchHasResults.get()) }
        screenModelScope.launchIO {
            try {
                installer.ensureLoaded()
            } catch (_: Throwable) {}
            if (initialQuery.isNotBlank()) search(initialQuery)
        }
        // In-library marking, same read-only (source, url) key set as browse.
        screenModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                mutableState.update { it.copy(favoritedKeys = keys) }
            }
        }
    }

    /** Switch Pinned-only vs All sources, then re-run the current query over the new source set. */
    fun setSourceFilter(filter: SourceFilter) {
        if (state.value.sourceFilter == filter) return
        mutableState.update { it.copy(sourceFilter = filter) }
        search(state.value.query)
    }

    /** Toggle the persisted "has results" display filter (hides sources that returned nothing). */
    fun toggleHasResults() {
        val newValue = !state.value.onlyShowHasResults
        sourcePreferences.novelGlobalSearchHasResults.set(newValue)
        mutableState.update { it.copy(onlyShowHasResults = newValue) }
    }

    // --- Long-press add-to-library, via the shared [NovelLibraryAdder]. The source id comes from the
    // tapped result's row since results span sources. ---

    fun onLongClickItem(item: NovelItem, sourceId: String) {
        screenModelScope.launchIO {
            val dialog = libraryAdder.onLongClick(item, sourceId, state.value.favoritedKeys)
            mutableState.update { it.copy(dialog = dialog) }
        }
    }

    fun addFromDuplicate(item: NovelItem, sourceId: String) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(dialog = libraryAdder.addToLibrary(item, sourceId)) }
        }
    }

    /** "Add to existing group": add, then merge it with the duplicates the user picked. */
    fun addToExistingGroup(item: NovelItem, sourceId: String, selectedIds: List<Long>) {
        screenModelScope.launchIO {
            val dialog = libraryAdder.addToExistingGroup(item, sourceId, selectedIds)
            mutableState.update { it.copy(dialog = dialog) }
        }
    }

    fun applyCategories(novelId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            libraryAdder.applyCategories(novelId, categoryIds)
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun confirmRemove(item: NovelItem, sourceId: String) {
        screenModelScope.launchIO {
            libraryAdder.confirmRemove(item, sourceId)
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun search(query: String) {
        searchJob?.cancel()
        // Match manga: don't show any source rows / loaders until a real search runs. A blank query
        // clears the list instead of leaving every source spinning forever.
        if (query.isBlank()) {
            mutableState.update { it.copy(query = "", results = emptyList()) }
            return
        }
        val pinned = sourcePreferences.pinnedNovelSources.get()
        val sources = selectGlobalSearchSources(getEnabledNovelSources.get(), pinned, state.value.sourceFilter)
        mutableState.update {
            it.copy(
                query = query,
                results = sources.map { source -> SourceSearchResult(source, SearchState.Loading) },
            )
        }
        searchJob = screenModelScope.launchIO {
            val semaphore = Semaphore(SEARCH_CONCURRENCY)
            sources.map { source ->
                async {
                    val result = semaphore.withPermit {
                        try {
                            SearchState.Success(source.searchNovels(query, 1))
                        } catch (e: Throwable) {
                            SearchState.Error("${e.javaClass.simpleName}: ${e.message ?: ""}")
                        }
                    }
                    mutableState.update { st ->
                        st.copy(
                            results = st.results
                                .map { if (it.source.id == source.id) it.copy(state = result) else it }
                                .sortedWith(globalSearchResultComparator(pinned)),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    /** Which sources the global search covers: only pinned, or all installed. Mirrors Mihon's filter. */
    enum class SourceFilter { All, PinnedOnly }
}

data class NovelGlobalSearchState(
    val query: String = "",
    val results: List<SourceSearchResult> = emptyList(),
    /** (source, url) pairs in the library, for in-library marking of results. */
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
    /** Defaults to PinnedOnly, matching the manga global search (empty until a source is pinned). */
    val sourceFilter: NovelGlobalSearchScreenModel.SourceFilter =
        NovelGlobalSearchScreenModel.SourceFilter.PinnedOnly,
    /** Hide sources that returned no results (persisted). */
    val onlyShowHasResults: Boolean = false,
    /** Active long-press dialog (add-duplicate / category picker / remove), or null. */
    val dialog: NovelBrowseDialog? = null,
) {
    /** Sources that have finished (Success or Error); with [total], drives the toolbar progress bar. */
    val progress: Int get() = results.count { it.state !is SearchState.Loading }
    val total: Int get() = results.size
}

/** One source's slice of a global search: the source plus its independent load state. */
data class SourceSearchResult(
    val source: NovelSource,
    val state: SearchState,
)

sealed interface SearchState {
    data object Loading : SearchState

    /** Completed; [novels] empty means the source returned no matches. */
    data class Success(val novels: List<NovelItem>) : SearchState

    data class Error(val message: String) : SearchState
}

/**
 * Pure source selection for the global search: keep all sources or pinned-only per [filter], ordered
 * pinned-first then by name. Extracted from the search loop so it's unit-testable without DI.
 */
internal fun selectGlobalSearchSources(
    all: List<NovelSource>,
    pinned: Set<String>,
    filter: NovelGlobalSearchScreenModel.SourceFilter,
): List<NovelSource> =
    all.filter { filter == NovelGlobalSearchScreenModel.SourceFilter.All || it.id in pinned }
        .sortedWith(compareBy({ it.id !in pinned }, { it.name.lowercase() }))

/** Orders search rows like Mihon's `SearchScreenModel.sortComparator`: sources with hits first, then
 *  pinned, then name, so empty / loading / errored sources sink below sources with results as each
 *  source lands. Re-applied on every row update. */
internal fun globalSearchResultComparator(pinned: Set<String>): Comparator<SourceSearchResult> =
    compareBy(
        { (it.state as? SearchState.Success)?.novels?.isEmpty() ?: true },
        { it.source.id !in pinned },
        { it.source.name.lowercase() },
    )

/** Whether a source row shows under the "has results" filter: always when off; only a non-empty
 *  Success when on (Loading / Error / empty sources are hidden). */
internal fun SourceSearchResult.isVisible(onlyShowHasResults: Boolean): Boolean {
    if (!onlyShowHasResults) return true
    val s = state
    return s is SearchState.Success && s.novels.isNotEmpty()
}
