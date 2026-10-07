package eu.kanade.tachiyomi.ui.library

import reikai.domain.entry.EntryId
import reikai.presentation.library.LibraryQuerySource
import reikai.presentation.library.SourceBadge
import tachiyomi.domain.library.model.LibraryManga

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val badges: Badges,
    // RK: ids of every source-manga collapsed into this entry (size > 1 means it is a merge group).
    // List (not LongArray) so the data-class equality the library StateFlow relies on still holds.
    val relatedMangaIds: List<Long> = emptyList(),
    // RK: neutral identity for the shared content layer's decision sites (cover model, badges,
    // selection). Every cross-type comparison keys on this, never on `id`, since a manga and a novel
    // can carry the same row id. Defaults to the manga id; NovelLibraryItem.toLibraryItem sets the
    // novel case.
    val entryId: EntryId = EntryId.Manga(libraryManga.id),
    // RK: upstream fields for the query AST, defaulted because the novel adapter builds this type too.
    // It fills them as well, since the shared query kernel reads both content types off the row.
    val sourceName: String = "",
    val sourceLanguage: String = "",
    // RK: the source name the EXH tag-search grammar matches against. getNameForMangaInfo() differs
    // from the AST's lowercased source name, so they stay separate rather than one changing the other.
    // Non-null only for metadata/gallery sources, which is also what selects the tag-search path.
    val metadataSourceName: String? = null,
    // RK: every member's source on a merged series, for the source search terms. Empty when not merged,
    // where the row's own source fields answer instead.
    val memberSources: List<LibraryQuerySource> = emptyList(),
) {
    val id: Long = libraryManga.id

    // RK: the EXH tag-search matcher moved to GallerySearchIndex.matches (GallerySearchIndex.kt).

    data class Badges(
        val downloadCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
        // RK --> the source-icon badge, typed for both content types (null when the source badge is off),
        // and one per distinct grouped source for a merge entry (empty when not merged or icons are off).
        val source: SourceBadge? = null,
        val mergedSources: List<SourceBadge> = emptyList(),
        // The novel's own source id, which its cover is fetched with. Null for manga rows.
        val coverSourceId: String? = null,
        // RK <--
    )
}
