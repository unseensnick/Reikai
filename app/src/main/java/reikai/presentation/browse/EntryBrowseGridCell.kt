package reikai.presentation.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import reikai.data.coil.NovelCover
import reikai.novel.host.NovelItem
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

/**
 * Content-neutral data for one browse-catalogue result. The cover is typed [Any] so a [MangaCover] or
 * a [NovelCover] flows through the shared cells unchanged (both have a registered Coil fetcher);
 * [favorite] drives the in-library dim + badge.
 */
data class EntryBrowseItemUi(
    val title: String,
    val cover: Any,
    val favorite: Boolean,
)

fun Manga.toEntryBrowseUi() = EntryBrowseItemUi(
    title = title,
    cover = MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    ),
    favorite = favorite,
)

/** [NovelItem] carries no favorite/source, so the caller supplies whether it's [inLibrary] and the
 *  source [site] (the novel cover's Referer). */
fun NovelItem.toEntryBrowseUi(inLibrary: Boolean, site: String?) = EntryBrowseItemUi(
    title = name,
    cover = NovelCover(url = cover, site = site, isNovelFavorite = inLibrary, lastModified = 0L),
    favorite = inLibrary,
)

/**
 * The single browse-result cell for both manga and novels: switches on [displayMode] and renders the
 * matching shared leaf (`MangaComfortableGridItem` / `MangaCompactGridItem` / `MangaListItem`), so the
 * two catalogues can't drift. Panorama renders as comfortable and cover-only as compact, matching how
 * browse has always ignored those two library-only modes. The grid/list container stays per-type.
 */
@Composable
fun EntryBrowseGridCell(
    ui: EntryBrowseItemUi,
    displayMode: LibraryDisplayMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
) {
    val coverAlpha = if (ui.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f
    when (displayMode) {
        LibraryDisplayMode.List -> MangaListItem(
            coverData = ui.cover,
            title = ui.title,
            coverAlpha = coverAlpha,
            badge = { InLibraryBadge(enabled = ui.favorite) },
            onClick = onClick,
            onLongClick = onLongClick,
            isSelected = isSelected,
        )
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
            coverData = ui.cover,
            title = ui.title,
            coverAlpha = coverAlpha,
            coverBadgeStart = { InLibraryBadge(enabled = ui.favorite) },
            onClick = onClick,
            onLongClick = onLongClick,
            isSelected = isSelected,
        )
        LibraryDisplayMode.ComfortableGrid, LibraryDisplayMode.ComfortableGridPanorama -> MangaComfortableGridItem(
            coverData = ui.cover,
            title = ui.title,
            coverAlpha = coverAlpha,
            coverBadgeStart = { InLibraryBadge(enabled = ui.favorite) },
            onClick = onClick,
            onLongClick = onLongClick,
            isSelected = isSelected,
        )
    }
}
