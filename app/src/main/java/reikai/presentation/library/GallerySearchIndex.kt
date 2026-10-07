package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.Text

/** Gallery EXH tags and alt-titles keyed by manga id, for the library's tag-search grammar. */
data class GallerySearchIndex(
    val tags: Map<Long, List<SearchTag>> = emptyMap(),
    val titles: Map<Long, List<SearchTitle>> = emptyMap(),
)

/**
 * Only the tag-search grammar reads the index, and only on gallery [rows], so both whole-table reads
 * run while a search is active over a library holding a gallery, and never otherwise. Manga only: the
 * tables are keyed by manga id, and novels have no gallery metadata.
 */
suspend fun gallerySearchIndexFor(
    query: String?,
    rows: List<LibraryItem>,
    loadTags: suspend () -> List<SearchTag>,
    loadTitles: suspend () -> List<SearchTitle>,
): GallerySearchIndex {
    if (query.isNullOrBlank() || rows.none { it.metadataSourceName != null }) return GallerySearchIndex()
    return GallerySearchIndex(
        tags = loadTags().groupBy { it.mangaId },
        titles = loadTitles().groupBy { it.mangaId },
    )
}

/**
 * The tag-search grammar (namespace:tag, wildcards, exclusion, exact) a gallery row gets on top of the
 * query AST every row matches through (libraryQueryMatches), which has no equivalent for it.
 * LibraryViewModel's search filter ORs the two for positive queries and ANDs them for exclusion-only
 * ones, because an excluded component this grammar cannot resolve passes vacuously and an OR would then
 * keep rows the AST excluded.
 */
fun LibraryItem.matchesMetadataQuery(parsedQuery: List<QueryComponent>, index: GallerySearchIndex): Boolean =
    parsedQuery.all { matchesComponent(it, index.tags[id], index.titles[id]) }

// A Namespace checks the indexed tags; a Text matches across the title, author, artist, description,
// source name, genres, tags and alt-titles. The component's excluded flag inverts the answer.
private fun LibraryItem.matchesComponent(
    component: QueryComponent,
    searchTags: List<SearchTag>?,
    searchTitles: List<SearchTitle>?,
): Boolean {
    val manga = libraryManga.manga
    val sourceName = metadataSourceName.orEmpty()
    val matched = when (component) {
        is Namespace -> {
            val tag = component.tag
            searchTags?.any {
                it.namespace.equals(component.namespace, true) &&
                    (tag == null || tag.asRegex(component.exact).containsMatchIn(it.name))
            } ?: false
        }
        is Text -> {
            val regex = component.asRegex(component.exact)
            regex.containsMatchIn(manga.title) ||
                (manga.author?.let { regex.containsMatchIn(it) } ?: false) ||
                (manga.artist?.let { regex.containsMatchIn(it) } ?: false) ||
                (manga.description?.let { regex.containsMatchIn(it) } ?: false) ||
                regex.containsMatchIn(sourceName) ||
                (manga.genre?.any { regex.containsMatchIn(it) } ?: false) ||
                (searchTags?.any { regex.containsMatchIn(it.name) } ?: false) ||
                (searchTitles?.any { regex.containsMatchIn(it.title) } ?: false)
        }
        else -> true
    }
    return matched != component.excluded
}
