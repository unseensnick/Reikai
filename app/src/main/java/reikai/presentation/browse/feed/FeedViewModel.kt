package reikai.presentation.browse.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelRepository
import reikai.domain.source.FeedSavedSearchRepository
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.FeedSavedSearch
import reikai.domain.source.model.SavedSearch
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.fillEntryRows
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** How many rows one feed may hold. Komikku's number, kept so a feed stays a glance. */
const val MAX_FEED_ROWS = 20

/**
 * The Browse feed: a row per source the reader added, showing what it has right now.
 *
 * Content-type neutral by construction. A row is stored as a [SourceKey] and an optional saved
 * search, and the two providers answer for their own sources; nothing here knows what a manga or a
 * plugin looks like.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class FeedViewModel(
    private val feedRepository: FeedSavedSearchRepository,
    private val savedSearchRepository: SavedSearchRepository,
    private val preferences: ReikaiSourcePreferences,
    private val novelRepository: NovelRepository,
    private val getManga: GetManga,
    sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    networkToLocalManga: NetworkToLocalManga,
    novelSourceManager: NovelSourceManager,
    getEnabledNovelSources: GetEnabledNovelSources,
) : ViewModel() {

    private val providers = listOf(
        MangaFeedProvider(sourceManager, sourcePreferences, networkToLocalManga),
        NovelFeedProvider(novelSourceManager, getEnabledNovelSources),
    )

    val state: StateFlow<FeedState>
        field = MutableStateFlow(FeedState())

    private var loadJob: Job? = null

    init {
        viewModelScope.launchIO {
            feedRepository.subscribeGlobal().collectLatest(::onFeedChanged)
        }
        viewModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                state.update { it.copy(favoritedKeys = keys) }
            }
        }
    }

    /**
     * Rebuilds the feed whenever its rows change. Every row refetches: adding or removing one is rare
     * and deliberate, so keeping results across a rebuild would buy little for the bookkeeping it
     * costs. A row a source no longer backs is dropped rather than shown as broken.
     */
    private suspend fun onFeedChanged(feeds: List<FeedSavedSearch>) {
        loadJob?.cancel()
        val searches = savedSearchRepository.getAll().associateBy { it.id }
        val entries = feeds.mapNotNull { feed ->
            val provider = providers.firstOrNull { it.contentType == feed.sourceKey.contentType }
            val row = provider?.source(feed.sourceKey) ?: return@mapNotNull null
            val search = feed.savedSearchId?.let { searches[it] }
            FeedEntry(
                feedId = feed.id,
                savedSearch = search,
                row = row.copy(id = feed.id.toString(), name = search?.name ?: row.name),
                sourceName = row.name,
                supportsLatest = provider.supportsLatest(row),
            )
        }
        state.update { it.copy(entries = entries, loaded = true) }
        startFilling(entries)
    }

    /** Runs every waiting row of [entries], replacing whatever pass was already going. */
    private fun startFilling(entries: List<FeedEntry>) {
        loadJob?.cancel()
        loadJob = viewModelScope.launchIO {
            fillEntryRows(
                rows = entries.map { it.row },
                group = { it.key.contentType },
                updateRows = { transform ->
                    state.update { current -> current.copy(entries = current.entries.withRows(transform)) }
                },
                load = { row ->
                    val entry = entries.first { it.row.id == row.id }
                    val provider = providers.first { it.contentType == row.key.contentType }
                    provider.load(row, entry.savedSearch).filterInLibrary()
                },
            )
        }
    }

    /** Hides what is already in the library, when the reader asked for that. */
    private fun List<Any>.filterInLibrary(): List<Any> {
        if (!preferences.hideInLibraryFeedItems.get()) return this
        val favorited = state.value.favoritedKeys
        return filterNot { entry ->
            when (entry) {
                is Manga -> entry.favorite
                is NovelItem -> favorited.any { it.second == entry.path }
                else -> false
            }
        }
    }

    fun openAddDialog() {
        viewModelScope.launchIO {
            if (feedRepository.countGlobal() >= MAX_FEED_ROWS) {
                state.update { it.copy(dialog = FeedDialog.TooManyRows) }
                return@launchIO
            }
            val sources = providers.flatMap { it.sources() }.sortedBy { it.name.lowercase() }
            state.update { it.copy(dialog = FeedDialog.PickSource(sources)) }
        }
    }

    /** Second step: the source's own saved searches, plus its plain listing. */
    fun pickSource(source: BrowseSearchRow) {
        viewModelScope.launchIO {
            val searches = savedSearchRepository.getBySource(source.key)
            state.update { it.copy(dialog = FeedDialog.PickSearch(source, searches)) }
        }
    }

    fun add(source: BrowseSearchRow, savedSearch: SavedSearch?) {
        viewModelScope.launchIO {
            feedRepository.insert(source.key, savedSearch?.id, global = true)
            state.update { it.copy(dialog = null) }
        }
    }

    fun confirmRemove(entry: FeedEntry) {
        state.update { it.copy(dialog = FeedDialog.Remove(entry)) }
    }

    fun remove(entry: FeedEntry) {
        viewModelScope.launchIO {
            feedRepository.delete(entry.feedId)
            state.update { it.copy(dialog = null) }
        }
    }

    /** The live row behind a result, so its in-library badge tracks what the reader does to it. */
    @Composable
    fun mangaState(initial: Manga): State<Manga> = produceState(initialValue = initial) {
        getManga.subscribe(initial.url, initial.source).filterNotNull().collectLatest { value = it }
    }

    /** Asks every row again. Nothing else does: a feed left open would otherwise go stale. */
    fun refresh() {
        val entries = state.value.entries
        state.update { current ->
            current.copy(entries = current.entries.map { it.copy(row = it.row.copy(state = EntrySearchState.Loading)) })
        }
        startFilling(entries.map { it.copy(row = it.row.copy(state = EntrySearchState.Loading)) })
    }

    fun dismissDialog() = state.update { it.copy(dialog = null) }
}

/** One row of the feed: what it shows, and what it was built from. */
@Immutable
data class FeedEntry(
    val feedId: Long,
    val savedSearch: SavedSearch?,
    val row: BrowseSearchRow,
    /** Named separately because a saved-search row is titled by the search, not by its source. */
    val sourceName: String,
    /** Where opening this row lands: its Latest, or Popular where the source has no latest. */
    val supportsLatest: Boolean,
)

@Immutable
data class FeedState(
    val entries: List<FeedEntry> = emptyList(),
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
    /** False until the first read of the table lands, so an empty feed is not claimed too early. */
    val loaded: Boolean = false,
    val dialog: FeedDialog? = null,
)

sealed interface FeedDialog {
    data class PickSource(val sources: List<BrowseSearchRow>) : FeedDialog
    data class PickSearch(val source: BrowseSearchRow, val searches: List<SavedSearch>) : FeedDialog
    data class Remove(val entry: FeedEntry) : FeedDialog
    data object TooManyRows : FeedDialog
}

/** Applies a row transform to the entries wrapping them, keeping each entry's own fields. */
private fun List<FeedEntry>.withRows(
    transform: (List<BrowseSearchRow>) -> List<BrowseSearchRow>,
): List<FeedEntry> {
    val byId = transform(map { it.row }).associateBy { it.id }
    return map { entry -> byId[entry.row.id]?.let { entry.copy(row = it) } ?: entry }
}
