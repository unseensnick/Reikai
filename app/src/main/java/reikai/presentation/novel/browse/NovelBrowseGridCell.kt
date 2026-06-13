package reikai.presentation.novel.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
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
        coverData = MangaCover(
            mangaId = 0L,
            sourceId = 0L,
            isMangaFavorite = inLibrary,
            url = item.cover.orEmpty(),
            lastModified = 0L,
        ),
        coverAlpha = if (inLibrary) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        coverBadgeStart = { InLibraryBadge(enabled = inLibrary) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
