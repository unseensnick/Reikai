package reikai.presentation.novel.globalsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
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

    // Stripped to a provider for the shared global search, which owns the query, the order, how many
    // sources run at once and when a search is worth re-running. What is left is the novel sources,
    // the one-source call, and the long-press half below, which is per content type.
    fun isPinned(source: NovelSource): Boolean = source.id in sourcePreferences.pinnedNovelSources.get()

    /** The novel sources a search covers. */
    suspend fun searchableSources(pinnedOnly: Boolean): List<NovelSource> {
        // Plugins load in the background and the registry answers "missing" for every source until
        // that finishes, so resolving the set any earlier searches nothing at all.
        try {
            installer.ensureLoaded()
        } catch (_: Throwable) {}
        val pinned = sourcePreferences.pinnedNovelSources.get()
        return getEnabledNovelSources.get().filter { !pinnedOnly || it.id in pinned }
    }

    suspend fun searchSource(source: NovelSource, query: String): List<NovelItem> =
        source.searchNovels(query, 1)

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
}

/**
 * What only the novel side answers: which of its results are already in the library, and the active
 * long-press dialog. The results themselves live in the shared global-search engine.
 */
data class NovelGlobalSearchState(
    /** (source, url) pairs in the library, for in-library marking of results. */
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
    /** Active long-press dialog (add-duplicate / category picker / remove), or null. */
    val dialog: NovelBrowseDialog? = null,
)
