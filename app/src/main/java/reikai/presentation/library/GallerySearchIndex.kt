package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text

/** Gallery EXH tags and alt-titles keyed by manga id, for the library's tag-search grammar. */
data class GallerySearchIndex(
    val tags: Map<Long, List<SearchTag>> = emptyMap(),
    val titles: Map<Long, List<SearchTitle>> = emptyMap(),
) {
    // One index lives for one filter pass, so each term is parsed once per search, not once per row.
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
