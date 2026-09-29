package reikai.presentation.browse.feed

import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import mihon.domain.manga.model.toDomainManga
import reikai.domain.library.ContentType
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.SourceKey
import reikai.domain.source.filter.MangaSavedSearchFilters
import reikai.domain.source.filter.NovelSavedSearchFilters
import reikai.domain.source.model.SavedSearch
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.browse.liveMangaRow
import reikai.presentation.browse.novelBrowseRow
import reikai.presentation.novel.browse.NovelSavedSearchRun
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager

/**
 * One content type's half of the feed: which of its sources a row can be built on, how to resolve one
 * back, and how to fetch the page a row shows. The engine owns everything describing the whole feed.
 */
interface FeedProvider {

    val contentType: ContentType

    /** Sources a feed row can be added for, the ones a reader has left enabled. */
    suspend fun sources(): List<BrowseSearchRow>

    /** The source behind [key], or null when it is no longer installed. */
    suspend fun source(key: SourceKey): BrowseSearchRow?

    /** Whether the source behind [row] can serve a Latest listing, which decides where a tap lands. */
    fun supportsLatest(row: BrowseSearchRow): Boolean

    /**
     * Page one of what the row shows: [savedSearch] when it carries one, else the source's Latest,
     * falling back to Popular where it has no latest listing.
     */
    suspend fun load(row: BrowseSearchRow, savedSearch: SavedSearch?): List<EntryBrowseRow>
}

/** The manga half, over Mihon's source manager. */
class MangaFeedProvider(
    private val sourceManager: SourceManager,
    private val getEnabledSources: GetEnabledSources,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
) : FeedProvider {

    private val filters = MangaSavedSearchFilters()

    override val contentType = ContentType.MANGA

    // The Sources tab's list, without the duplicate row it adds for the last-used source.
    override suspend fun sources(): List<BrowseSearchRow> =
        getEnabledSources.subscribe().first()
            .filterNot { it.isUsedLast }
            .mapNotNull { sourceManager.get(it.id) as? CatalogueSource }
            .map(::toRow)

    override suspend fun source(key: SourceKey): BrowseSearchRow? =
        (key as? SourceKey.Manga)?.let { sourceManager.get(it.id) as? CatalogueSource }?.let(::toRow)

    override fun supportsLatest(row: BrowseSearchRow) = (row.source as CatalogueSource).supportsLatest

    override suspend fun load(row: BrowseSearchRow, savedSearch: SavedSearch?): List<EntryBrowseRow> {
        val source = row.source as CatalogueSource
        val page = when {
            savedSearch != null -> {
                // Onto a list the source builds now, so anything the search does not carry keeps the
                // source's own default. Same rule the catalogue applies when a chip is tapped.
                val filterList = source.getFilterList()
                savedSearch.filtersJson?.let { filters.decode(it, filterList) }
                source.getSearchManga(1, savedSearch.query.orEmpty(), filterList)
            }
            source.supportsLatest -> source.getLatestUpdates(1)
            else -> source.getPopularManga(1)
        }
        // Made local before they are shown, so each row can follow its stored manga.
        return page.mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
            .let { networkToLocalManga(it) }
            .map { liveMangaRow(it, getManga.subscribe(it.url, it.source)) }
    }

    private fun toRow(source: CatalogueSource) = BrowseSearchRow(
        key = SourceKey.Manga(source.id),
        name = source.name,
        lang = source.lang,
        isPinned = false,
        state = EntrySearchState.Loading,
        source = source,
    )
}

/** The light-novel half, over the plugin manager. */
class NovelFeedProvider(
    private val sourceManager: NovelSourceManager,
    private val getEnabledSources: GetEnabledNovelSources,
    /** The library's keys, which each result row reads its in-library badge off. */
    private val favorited: StateFlow<FavoritedNovels>,
) : FeedProvider {

    private val filters = NovelSavedSearchFilters()

    override val contentType = ContentType.NOVELS

    override suspend fun sources(): List<BrowseSearchRow> = getEnabledSources.get().map(::toRow)

    override suspend fun source(key: SourceKey): BrowseSearchRow? =
        (key as? SourceKey.Novel)?.let { sourceManager.get(it.id) }?.let(::toRow)

    override fun supportsLatest(row: BrowseSearchRow) = (row.source as NovelSource).supportsLatest

    override suspend fun load(row: BrowseSearchRow, savedSearch: SavedSearch?): List<EntryBrowseRow> {
        val source = row.source as NovelSource
        return page(source, savedSearch).map { novelBrowseRow(it, source.id, favorited) }
    }

    private suspend fun page(source: NovelSource, savedSearch: SavedSearch?): List<NovelItem> {
        val defaults = source.filters?.defaultState()
        val stored = savedSearch?.filtersJson
            ?.let { json -> defaults?.let { filters.decode(json, it) } }
            ?: defaults
        if (savedSearch == null) {
            val listing = if (source.supportsLatest) NovelListing.Latest else NovelListing.Popular
            return source.browse(listing, page = 1, defaults).items
        }
        return when (val run = NovelSavedSearchRun.of(source.filters?.applyToSearch == true, savedSearch.query)) {
            is NovelSavedSearchRun.SearchWithFilters -> source.search(run.query, page = 1, stored).items
            is NovelSavedSearchRun.PlainSearch -> source.search(run.query, page = 1, defaults).items
            NovelSavedSearchRun.FilteredPopular -> source.browse(NovelListing.Popular, page = 1, stored).items
        }
    }

    private fun toRow(source: NovelSource) = BrowseSearchRow(
        key = SourceKey.Novel(source.id),
        name = source.name,
        lang = source.lang,
        isPinned = false,
        state = EntrySearchState.Loading,
        source = source,
        format = source.format,
    )
}
