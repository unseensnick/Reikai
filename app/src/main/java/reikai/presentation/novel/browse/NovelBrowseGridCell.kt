package reikai.presentation.novel.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import reikai.data.coil.NovelCover
import reikai.novel.host.NovelItem

/**
 * Browse-result cell, a clone of Mihon's `BrowseSourceComfortableGridItem` retyped for [NovelItem],
 * so the LN browse grid matches the manga catalogue. The cover loads through the novel cover pipeline
 * ([NovelCover]), which sends the source [site] as a Referer; [inLibrary] dims the cover + shows the badge.
 */
@Composable
fun NovelBrowseGridCell(
    item: NovelItem,
    inLibrary: Boolean,
    site: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaComfortableGridItem(
        title = item.name,
        coverData = novelCover(item, inLibrary, site),
        coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = { InLibraryBadge(enabled = inLibrary) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** Compact-grid variant (title overlaid on the cover), the novel twin of `BrowseSourceCompactGrid`'s cell. */
@Composable
fun NovelBrowseCompactGridCell(
    item: NovelItem,
    inLibrary: Boolean,
    site: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaCompactGridItem(
        title = item.name,
        coverData = novelCover(item, inLibrary, site),
        coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = { InLibraryBadge(enabled = inLibrary) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** List variant (small cover + title row), the novel twin of `BrowseSourceList`'s cell. */
@Composable
fun NovelBrowseListCell(
    item: NovelItem,
    inLibrary: Boolean,
    site: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaListItem(
        title = item.name,
        coverData = novelCover(item, inLibrary, site),
        coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = { InLibraryBadge(enabled = inLibrary) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

private fun novelCover(item: NovelItem, inLibrary: Boolean, site: String?) = NovelCover(
    url = item.cover,
    site = site,
    isNovelFavorite = inLibrary,
    lastModified = 0L,
)
