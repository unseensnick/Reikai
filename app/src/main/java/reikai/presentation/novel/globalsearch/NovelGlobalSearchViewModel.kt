package reikai.presentation.novel.globalsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
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
import reikai.domain.novel.NovelRepository
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelCategoryTarget
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.util.lang.launchIO

/** Max sources searched concurrently, matching the manga global search's throttle. */
private const val SEARCH_CONCURRENCY = 5

/**
 * Cross-source light-novel search. Fans [NovelSource.searchNovels] out across every installed source
 * under a [Semaphore], updating each source's row independently as it completes so results fill in
 * progressively (mirrors Mihon's `SearchViewModel`).
 */
@AssistedInject
class NovelGlobalSearchViewModel(
    @Assisted initialQuery: String,
    private val installer: LnPluginInstaller,
    private val novelRepository: NovelRepository,
    private val libraryAdder: NovelLibraryAdder,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val getEnabledNovelSources: GetEnabledNovelSources,
) : ViewModel() {

    val state: StateFlow<NovelGlobalSearchState>
        field = MutableStateFlow<NovelGlobalSearchState>(NovelGlobalSearchState(query = initialQuery))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String): NovelGlobalSearchViewModel
    }

    private var searchJob: Job? = null

    init {
        state.update { it.copy(onlyShowHasResults = sourcePreferences.novelGlobalSearchHasResults.get()) }
        if (initialQuery.isNotBlank()) search(initialQuery)
        // In-library marking, same read-only (source, url) key set as browse.
        viewModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                state.update { it.copy(favoritedKeys = keys) }
            }
        }
    }

    /** Switch Pinned-only vs All sources, then re-run the current query over the new source set. */
    fun setSourceFilter(filter: SourceFilter) {
        if (state.value.sourceFilter == filter) return
        state.update { it.copy(sourceFilter = filter) }
        search(state.value.query)
    }

    /** Toggle the persisted "has results" display filter (hides sources that returned nothing). */
    fun toggleHasResults() {
        val newValue = !state.value.onlyShowHasResults
        sourcePreferences.novelGlobalSearchHasResults.set(newValue)
        state.update { it.copy(onlyShowHasResults = newValue) }
    }

    // --- Long-press add-to-library, via the shared [NovelLibraryAdder]. The source id comes from the
    // tapped result's row since results span sources. ---

    fun onLongClickItem(item: NovelItem, sourceId: String) {
        viewModelScope.launchIO {
            val dialog = libraryAdder.onLongClick(item, sourceId, state.value.favoritedKeys)
            state.update { it.copy(dialog = dialog) }
        }
    }

    fun addFromDuplicate(item: NovelItem, sourceId: String) {
        viewModelScope.launchIO {
            state.update { it.copy(dialog = libraryAdder.addToLibrary(item, sourceId)) }
        }
    }

    /** Materialize the browsed result as a target row, then raise the migrate dialog on it. The
     *  materialize is a source round trip, so it runs here rather than in a composable's own scope. */
    fun startMigrate(duplicateId: Long, item: NovelItem, sourceId: String) {
        viewModelScope.launchIO {
            val target = libraryAdder.materialize(item, sourceId) ?: return@launchIO
            state.update {
                it.copy(dialog = NovelBrowseDialog.Migrate(currentId = duplicateId, targetId = target.id))
            }
        }
    }

    /** "Add to existing group": add, then merge it with the duplicates the user picked. */
    fun addToExistingGroup(item: NovelItem, sourceId: String, selectedIds: List<Long>) {
        viewModelScope.launchIO {
            val dialog = libraryAdder.addToExistingGroup(item, sourceId, selectedIds)
            state.update { it.copy(dialog = dialog) }
        }
    }

    fun applyCategories(target: NovelCategoryTarget, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            libraryAdder.confirmCategories(target, categoryIds)
            state.update { it.copy(dialog = null) }
        }
    }

    fun confirmRemove(item: NovelItem, sourceId: String) {
        viewModelScope.launchIO {
            libraryAdder.confirmRemove(item, sourceId)
            state.update { it.copy(dialog = null) }
        }
    }

    fun dismissDialog() = state.update { it.copy(dialog = null) }

    fun search(query: String) {
        searchJob?.cancel()
        // Match manga: don't show any source rows / loaders until a real search runs. A blank query
        // clears the list instead of leaving every source spinning forever.
        if (query.isBlank()) {
            state.update { it.copy(query = "", results = emptyList()) }
            return
        }
        searchJob = viewModelScope.launchIO {
            // Plugins load in the background and the registry answers "missing" for every source
            // until that finishes, so resolving the set any earlier searches nothing at all.
            try {
                installer.ensureLoaded()
            } catch (_: Throwable) {}
            val pinned = sourcePreferences.pinnedNovelSources.get()
            val sources = selectGlobalSearchSources(getEnabledNovelSources.get(), pinned, state.value.sourceFilter)
            state.update {
                it.copy(
                    query = query,
                    results = sources.map { source -> SourceSearchResult(source, SearchState.Loading) },
                )
            }
            val semaphore = Semaphore(SEARCH_CONCURRENCY)
            sources.map { source ->
                async {
                    val result = semaphore.withPermit {
                        try {
                            SearchState.Success(source.searchNovels(query, 1))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            SearchState.Error("${e.javaClass.simpleName}: ${e.message ?: ""}")
                        }
                    }
                    state.update { st ->
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
    val sourceFilter: NovelGlobalSearchViewModel.SourceFilter =
        NovelGlobalSearchViewModel.SourceFilter.PinnedOnly,
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
    filter: NovelGlobalSearchViewModel.SourceFilter,
): List<NovelSource> =
    all.filter { filter == NovelGlobalSearchViewModel.SourceFilter.All || it.id in pinned }
        .sortedWith(compareBy({ it.id !in pinned }, { it.name.lowercase() }))

/** Orders search rows like Mihon's `SearchViewModel.sortComparator`: sources with hits first, then
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
