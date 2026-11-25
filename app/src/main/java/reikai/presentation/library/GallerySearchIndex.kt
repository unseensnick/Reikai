package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Gallery EXH tags and alt-titles keyed by manga id, for the library's tag-search grammar. */
data class GallerySearchIndex(
    val tags: Map<Long, List<SearchTag>> = emptyMap(),
    val titles: Map<Long, List<SearchTitle>> = emptyMap(),
) {
    // One index serves every filter pass while its tables stand, so each term is parsed once, not once per row.
    private val terms = SearchEngine()

    /**
     * The tag-search grammar (namespace:tag, wildcards, exact) a gallery row answers one query term
     * with, on top of the query AST every row matches through (libraryQueryMatches), which has no
     * equivalent for it. The AST asks per term, so a `-` term excludes a row either grammar finds it in.
     */
    fun matches(row: LibraryItem, term: String): Boolean {
        if (row.metadataSourceName == null) return false
        val components = terms.parseQuery(term)
        return components.isNotEmpty() && components.all { row.matchesComponent(it, tags[row.id], titles[row.id]) }
    }
}

/**
 * Both whole gallery tables grouped by manga id, regrouped only when a table changes, so a held search
 * does not re-read them on every library tick. Manga only: the tables are keyed by manga id, and novels
 * have no gallery metadata.
 */
fun gallerySearchIndexChanges(
    tags: Flow<List<SearchTag>>,
    titles: Flow<List<SearchTitle>>,
): Flow<GallerySearchIndex> = combine(tags, titles) { tagRows, titleRows ->
    GallerySearchIndex(tags = tagRows.groupBy { it.mangaId }, titles = titleRows.groupBy { it.mangaId })
}

/** The index a search for [query] reads: an empty one without a query, so neither table is read until one is typed. */
fun gallerySearchIndexFor(query: String?, index: Flow<GallerySearchIndex>): Flow<GallerySearchIndex> =
    if (query.isNullOrBlank()) flowOf(GallerySearchIndex()) else index

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
