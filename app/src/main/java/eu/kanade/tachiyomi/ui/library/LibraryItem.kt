package eu.kanade.tachiyomi.ui.library

import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.Text
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.source.model.Source

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val badges: Badges,
    // RK: ids of every source-manga collapsed into this entry (size > 1 means it is a merge group).
    // List (not LongArray) so the data-class equality the library StateFlow relies on still holds.
    val relatedMangaIds: List<Long> = emptyList(),
    // RK: the gallery's indexed EXH tags (E-Hentai/nHentai/etc.), for library tag search. Null for
    // ordinary manga that have no captured metadata.
    val searchTags: List<SearchTag>? = null,
    // RK: the gallery's indexed alt-titles (japanese / english / short), so a tag-search term can
    // match a title variant other than the displayed one. Null for ordinary manga.
    val searchTitles: List<SearchTitle>? = null,
    // RK: neutral identity for the shared content layer's decision sites (cover model, badges,
    // selection). Every cross-type comparison keys on this, never on `id`, since a manga and a novel
    // can carry the same row id. Defaults to the manga id; NovelLibraryItem.toLibraryItem sets the
    // novel case.
    val entryId: EntryId = EntryId.Manga(libraryManga.id),
    // Upstream fields for the query AST. Defaulted because the novel adapter builds this type too and
    // novels filter through their own matcher, never the AST.
    val sourceName: String = "",
    val sourceLanguage: String = "",
    // RK: the source name the EXH tag-search grammar matches against. getNameForMangaInfo() differs
    // from the AST's lowercased source name, so they stay separate rather than one changing the other.
    // Non-null only for metadata/gallery sources, which is also what selects the tag-search path.
    val metadataSourceName: String? = null,
) {
    val id: Long = libraryManga.id

    // RK --> tag-search engine for adult/metadata sources. Upstream matches every entry through the
    // query AST (mihon.feature.library.matches); a gallery entry ALSO gets this structured grammar
    // (namespace:tag, wildcards, exclusion, exact), which the AST has no equivalent for. The caller
    // (LibraryScreenModel's search filter) ORs the two grammars for positive queries and ANDs them
    // for exclusion-only ones, because an excluded component this grammar cannot resolve passes
    // vacuously and an OR would then keep rows the AST kernel excluded.
    fun matchesMetadataQuery(parsedQuery: List<QueryComponent>): Boolean =
        parsedQuery.all { matchesComponent(it, metadataSourceName.orEmpty()) }

    // Match one parsed query component against this entry, honouring its excluded flag. A Namespace
    // checks the indexed tags (namespace + optional tag pattern); a Text matches across the entry's
    // title, author, artist, description, source name, genres, tags and alt-titles.
    private fun matchesComponent(component: QueryComponent, sourceName: String): Boolean {
        val manga = libraryManga.manga
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
    // RK <--

    data class Badges(
        val downloadCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
        // RK: source-icon badge data (null when the source badge is off)
        val source: Source? = null,
        // RK: the grouped sources for a merge entry (empty when not merged), for the merge badge.
        val mergedSources: List<Source> = emptyList(),
        // RK --> novel-cover pipeline: the source site (cover Referer) + the source icon URL for the
        // source badge, since a disguised novel has no real Mihon Source. Null for manga rows.
        val coverSite: String? = null,
        val sourceIconUrl: String? = null,
        // The grouped sources' icon URLs for a merged NOVEL's badge (coil-loaded; novels have no
        // Mihon Source bitmap). Empty when not merged or the merge-icon setting is off.
        val mergedSourceIconUrls: List<String> = emptyList(),
        // RK <--
    )
}
