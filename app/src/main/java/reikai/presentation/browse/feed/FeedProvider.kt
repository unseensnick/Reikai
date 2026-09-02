package reikai.presentation.browse.feed

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.domain.manga.model.toDomainManga
import reikai.domain.library.ContentType
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.SourceKey
import reikai.domain.source.filter.MangaSavedSearchFilters
import reikai.domain.source.filter.NovelSavedSearchFilters
import reikai.domain.source.model.SavedSearch
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.langCode
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.novel.browse.buildOptions
import reikai.presentation.novel.browse.defaultFilterValues
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

/**
 * One content type's half of the feed: which of its sources a row can be built on, how to resolve one
 * back, and how to fetch the page a row shows. The engine owns everything describing the whole feed.
 */
interface FeedProvider {

    val contentType: ContentType

    /** Sources a feed row can be added for, the ones a reader has left enabled. */
    fun sources(): List<BrowseSearchRow>

    /** The source behind [key], or null when it is no longer installed. */
    fun source(key: SourceKey): BrowseSearchRow?

    /** Whether the source behind [row] can serve a Latest listing, which decides where a tap lands. */
    fun supportsLatest(row: BrowseSearchRow): Boolean

    /**
     * Page one of what the row shows: [savedSearch] when it carries one, else the source's Latest,
     * falling back to Popular where it has no latest listing.
     */
    suspend fun load(row: BrowseSearchRow, savedSearch: SavedSearch?): List<Any>

    /**
     * Whether [entry], as [row] returned it, is already in the library. On the provider because only
     * it knows how its own type is identified: a manga carries the answer, a novel is a source plus a
     * path and needs both halves, so a shared branch on the payload type gets the novel case wrong.
     */
    fun isInLibrary(row: BrowseSearchRow, entry: Any, favoritedKeys: Set<Pair<String, String>>): Boolean
}

/** The manga half, over Mihon's source manager. */
class MangaFeedProvider(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : FeedProvider {

    private val filters = MangaSavedSearchFilters()

    override val contentType = ContentType.MANGA

    override fun sources(): List<BrowseSearchRow> {
        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabled = sourcePreferences.disabledSources.get()
        return sourceManager.getAll()
            .filterIsInstance<CatalogueSource>()
            // Same predicate as GetEnabledSources, local source included: it has no language to
            // enable, so filtering on language alone is what hides it from its own lists.
            .filter { (it.lang in enabledLanguages || it.isLocal()) && "${it.id}" !in disabled }
            .map(::toRow)
    }

    override fun source(key: SourceKey): BrowseSearchRow? =
        (key as? SourceKey.Manga)?.let { sourceManager.get(it.id) as? CatalogueSource }?.let(::toRow)

    override fun supportsLatest(row: BrowseSearchRow) = (row.source as CatalogueSource).supportsLatest

    override suspend fun load(row: BrowseSearchRow, savedSearch: SavedSearch?): List<Any> {
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
        // Made local before they are shown, so the in-library badge has a row to resolve against.
        return page.mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
            .let { networkToLocalManga(it) }
    }

    override fun isInLibrary(
        row: BrowseSearchRow,
        entry: Any,
        favoritedKeys: Set<Pair<String, String>>,
    ): Boolean = (entry as? Manga)?.favorite == true

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
) : FeedProvider {

    private val filters = NovelSavedSearchFilters()

    override val contentType = ContentType.NOVELS

    override fun sources(): List<BrowseSearchRow> = getEnabledSources.get().map(::toRow)

    override fun source(key: SourceKey): BrowseSearchRow? =
        (key as? SourceKey.Novel)?.let { sourceManager.get(it.id) }?.let(::toRow)

    override fun supportsLatest(row: BrowseSearchRow) = (row.source as NovelSource).supportsLatest

    override suspend fun load(row: BrowseSearchRow, savedSearch: SavedSearch?): List<Any> {
        val source = row.source as NovelSource
        // A plugin's search endpoint takes no options, so a saved search carrying a query is a
        // search and everything else runs as a listing. The catalogue applies the same rule.
        val query = savedSearch?.query
        if (!query.isNullOrBlank()) return source.searchNovels(query, page = 1)

        val defaults = defaultFilterValues(source.filters)
        val values = savedSearch?.filtersJson?.let { filters.decode(it, defaults) } ?: defaults
        return source.popularNovels(
            page = 1,
            optionsJson = buildOptions(source.filters, values, showLatest = source.supportsLatest),
        )
    }

    override fun isInLibrary(
        row: BrowseSearchRow,
        entry: Any,
        favoritedKeys: Set<Pair<String, String>>,
    ): Boolean {
        val item = entry as? NovelItem ?: return false
        return ((row.source as NovelSource).id to item.path) in favoritedKeys
    }

    private fun toRow(source: NovelSource) = BrowseSearchRow(
        key = SourceKey.Novel(source.id),
        name = source.name,
        lang = source.langCode(),
        isPinned = false,
        state = EntrySearchState.Loading,
        source = source,
    )
}
