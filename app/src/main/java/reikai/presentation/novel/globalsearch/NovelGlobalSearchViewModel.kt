package reikai.presentation.novel.globalsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.NovelRepository
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.presentation.novel.browse.NovelAddFlow
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.lang.launchIO

/**
 * The novel provider behind the shared global search, which owns the query, the order, the fan-out
 * and its concurrency ([reikai.presentation.browse.fillEntryRows]) and when a search is worth re-running.
 * What is left here is the novel sources, the one-source call, and the long-press add flow.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelGlobalSearchViewModel(
    private val installer: LnPluginInstaller,
    private val novelRepository: NovelRepository,
    private val libraryAdder: NovelLibraryAdder,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val getEnabledNovelSources: GetEnabledNovelSources,
) : ViewModel() {

    val state: StateFlow<NovelGlobalSearchState>
        field = MutableStateFlow(NovelGlobalSearchState())

    init {
        // In-library marking, same read-only (source, url) key set as browse.
        viewModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                state.update { it.copy(favoritedKeys = keys) }
            }
        }
    }

    fun isPinned(source: NovelSource): Boolean = source.id in sourcePreferences.pinnedNovelSources.get()

    /** The novel sources a search covers. */
    suspend fun searchableSources(pinnedOnly: Boolean): List<NovelSource> {
        // Plugins load in the background and the registry answers "missing" for every source until
        // that finishes, so resolving the set any earlier searches nothing at all.
        runCatchingCancellable { installer.ensureLoaded() }
        val pinned = sourcePreferences.pinnedNovelSources.get()
        return getEnabledNovelSources.get().filter { !pinnedOnly || it.id in pinned }
    }

    suspend fun searchSource(source: NovelSource, query: String): List<NovelItem> =
        source.search(query, 1, filters = null).items

    // The source id comes from each result's row, since results span sources.
    val addFlow = NovelAddFlow(libraryAdder, viewModelScope)
}

/**
 * What only the novel side answers: which of its results are already in the library. The results
 * themselves live in the shared global-search engine.
 */
data class NovelGlobalSearchState(
    /** (source, url) pairs in the library, for in-library marking of results. */
    val favoritedKeys: FavoritedNovels = FavoritedNovels.None,
)
