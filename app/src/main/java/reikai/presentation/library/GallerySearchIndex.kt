package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle

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
