package reikai.presentation.novel.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import reikai.novel.host.NovelItem
import tachiyomi.domain.manga.model.MangaCover

/**
 * Browse-result cell, a clone of Mihon's `BrowseSourceComfortableGridItem` retyped for [NovelItem],
 * so the LN browse grid matches the manga catalogue. The cover loads by URL via a synthetic
 * [MangaCover]; [inLibrary] dims the cover and shows the badge.
 *
 * NOTE: some LN sources hand the browse list a small/wide thumbnail URL (e.g. Novel Bin's
 * `images.novelbin.com/novel_200_89/...`) rather than the full cover, so those covers look cropped.
 * That is a cover-URL extraction issue in the plugin host (S2), not the cell's aspect ratio.
 */
@Composable
fun NovelBrowseGridCell(
    item: NovelItem,
    inLibrary: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaComfortableGridItem(
        title = item.name,
        coverData = novelCover(item, inLibrary),
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaCompactGridItem(
        title = item.name,
        coverData = novelCover(item, inLibrary),
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MangaListItem(
        title = item.name,
        coverData = novelCover(item, inLibrary),
        coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = { InLibraryBadge(enabled = inLibrary) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/** Synthetic source-less cover (sourceId 0); see [NovelBrowseGridCell] for why covers load by URL. */
private fun novelCover(item: NovelItem, inLibrary: Boolean) = MangaCover(
    mangaId = 0L,
    sourceId = 0L,
    isMangaFavorite = inLibrary,
    url = item.cover.orEmpty(),
    lastModified = 0L,
)
