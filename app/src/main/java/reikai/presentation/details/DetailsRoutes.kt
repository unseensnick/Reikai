package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.item
import reikai.presentation.browse.manga
import reikai.presentation.novel.details.NovelScreen

// The one place a series' details page is built, for both types, so a change to how a page is addressed
// lands here rather than at each caller. DetailsRoutesGuardTest fails on a construction anywhere else.

/** A manga's details page by its row id. */
fun EntryId.Manga.detailsScreen(fromSource: Boolean = false): Screen = MangaScreen(rawId, fromSource)

/**
 * The details page of [this] entry, or null for a novel since removed: the novel page is keyed by source
 * and url, so a novel id needs its stored row.
 */
suspend fun EntryId.detailsScreen(novels: NovelRepository, fromSource: Boolean = false): Screen? = when (this) {
    is EntryId.Manga -> detailsScreen(fromSource)
    is EntryId.Novel -> novels.getById(rawId)?.let { novelDetailsAt(it.source, it.url, fromSource = fromSource) }
}

/** A novel's details page by its source and url; [listingCover] stands in for a placeholder cover. */
fun novelDetailsAt(
    sourceId: String,
    url: String,
    listingCover: String? = null,
    fromSource: Boolean = false,
): Screen = NovelScreen(sourceId, url, listingCover, fromSource)

/** The details page a browse result of the source at [sourceKey] opens, which is always from that source. */
fun EntryBrowseRow.detailsScreen(sourceKey: SourceKey): Screen = when (sourceKey) {
    is SourceKey.Manga -> EntryId.Manga(manga.id).detailsScreen(fromSource = true)
    is SourceKey.Novel -> novelDetailsAt(sourceKey.id, item.path, item.cover, fromSource = true)
}

/**
 * Whether this screen is [entry]'s details page. A novel page is matched by [sourceId] and [url], which
 * is what it is keyed by; a manga page by its id alone.
 */
fun Screen.shows(entry: EntryId, sourceId: String, url: String): Boolean = when (entry) {
    is EntryId.Manga -> (this as? MangaScreen)?.mangaId == entry.rawId
    is EntryId.Novel -> this is NovelScreen && this.sourceId == sourceId && novelUrl == url
}
