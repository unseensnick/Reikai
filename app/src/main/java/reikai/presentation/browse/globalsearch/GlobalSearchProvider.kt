package reikai.presentation.browse.globalsearch

import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchViewModel
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.novel.source.NovelSource
import reikai.presentation.novel.globalsearch.NovelGlobalSearchViewModel

/**
 * One content type's half of a global search. A provider answers which of its sources to search and
 * runs one of them; it never orders the merged list, applies the chip or the has-results toggle, or
 * decides when a search is worth re-running, because those describe the whole list and the engine
 * owns them.
 */
interface GlobalSearchProvider {

    val contentType: ContentType

    /** The sources this provider would search under [filter], each as a row that is still loading. */
    suspend fun sources(filter: SearchSourceFilter): List<BrowseSearchRow>

    /** Run [query] against the source behind [row]. Throwing is the engine's cue to mark it errored. */
    suspend fun search(row: BrowseSearchRow, query: String): List<Any>
}

/** The manga half, over Mihon's live [GlobalSearchViewModel]. */
class MangaGlobalSearchProvider(private val model: GlobalSearchViewModel) : GlobalSearchProvider {

    override val contentType = ContentType.MANGA

    override suspend fun sources(filter: SearchSourceFilter): List<BrowseSearchRow> =
        model.searchableSources(pinnedOnly = filter == SearchSourceFilter.PinnedOnly).map { source ->
            BrowseSearchRow(
                key = SourceKey.Manga(source.id),
                name = source.name,
                lang = source.lang,
                isPinned = model.isPinned(source),
                state = EntrySearchState.Loading,
                source = source,
            )
        }

    override suspend fun search(row: BrowseSearchRow, query: String): List<Any> =
        model.searchSource(row.source as eu.kanade.tachiyomi.source.Source, query)
}

/** The light-novel half, over [NovelGlobalSearchViewModel]. */
class NovelGlobalSearchProvider(private val model: NovelGlobalSearchViewModel) : GlobalSearchProvider {

    override val contentType = ContentType.NOVELS

    override suspend fun sources(filter: SearchSourceFilter): List<BrowseSearchRow> =
        model.searchableSources(pinnedOnly = filter == SearchSourceFilter.PinnedOnly).map { source ->
            BrowseSearchRow(
                key = SourceKey.Novel(source.id),
                name = source.name,
                lang = source.lang,
                isPinned = model.isPinned(source),
                state = EntrySearchState.Loading,
                source = source,
            )
        }

    override suspend fun search(row: BrowseSearchRow, query: String): List<Any> =
        model.searchSource(row.source as NovelSource, query)
}
