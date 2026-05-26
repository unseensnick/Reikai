package yokai.presentation.library.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.isLocal
import yokai.domain.manga.models.cover
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Per-cell rendering shared between the regular grid and staggered grid branches of
 * `LibraryContent`. Resolves the badge / cover prefs into the right [MangaComfortableGridItem] /
 * [MangaCompactGridItem] call without duplicating the body across the two grid scopes (their
 * `items` overloads have incompatible scope types so the call sites cannot share code directly).
 *
 * Extracted from `LibraryContent.kt` in Phase 8 C7a so the surrounding composable can be
 * type-parameterized over `T : LibraryItem` in C7c. The novel-side parallel is
 * [yokai.presentation.library.novels.NovelLibraryGridCell] (Phase 8 C8).
 *
 * @param coverAspectRatio passed through to the underlying cell. Null lets the cover render at
 *   its image's intrinsic ratio (staggered grid with `uniformGrid` off); the default 2:3 is
 *   used by the fixed grid.
 */
@Composable
fun MangaLibraryGridCell(
    item: LibraryItem.Manga,
    libraryLayout: Int,
    outlineOnCovers: Boolean,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    hideStartReadingButton: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    /**
     * True when at least one library item is currently selected. In selection mode a tap on a
     * cell toggles its membership in the set (matching the legacy `ActionMode` behavior);
     * outside selection mode a tap navigates to manga details. Also suppresses the
     * continue-reading overlay so an in-mode tap on the play button doesn't bypass selection.
     */
    selectionActive: Boolean,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    onContinueReading: (Manga) -> Unit,
    /**
     * Cover aspect ratio. Default 2:3 (book) for the regular grid path; pass `null` for the
     * staggered grid so each cover renders at its image's intrinsic ratio. Regular-grid callers
     * with Uniform grid covers off should pass the per-cover cached ratio from
     * `MangaCoverMetadata` (BOOK fallback), not `null`, so the cell has a stable height while
     * Coil's AsyncImage is still loading the placeholder. See `LibraryGridHolder.setFreeformCoverRatio`.
     */
    coverAspectRatio: Float? = MangaCoverRatio.BOOK,
) {
    val manga = item.libraryManga.manga
    // manga.id keys both lookups: cover() rebuilds a thin wrapper and title hits the
    // Injekt-backed CustomMangaManager for favorited entries; both are stable per row.
    val coverData = remember(manga.id) { manga.cover() }
    val title = remember(manga.id) { manga.title }
    // unreadBadgeType matches the legacy RadioGroup binding (see LibraryHolder.setUnreadBadge):
    // 0 (or negative) = hide, 1 = dot, 2 = count. Pass 0 unread when hidden so the badge slot
    // collapses; otherwise the cell decides between count and dot via [unreadDot].
    val unreadCount = if (unreadBadgeType > 0) item.libraryManga.unread else 0
    val unreadDot = unreadBadgeType == 1
    val downloadCount = if (showDownloadBadge) {
        item.downloadCount.toInt().coerceAtLeast(0)
    } else {
        0
    }
    val lang = if (showLanguageBadge) item.language.takeIf { it.isNotBlank() } else null
    val isLocal = remember(manga.id) { manga.isLocal() }
    // Merge-group size for the source-count badge at the cover's top-end. Populated by
    // MangaLibraryGrouping.collapse on each rendered leader; empty for standalone items and
    // for every item in dynamic groupings (which don't collapse). The MangaGridCover pill
    // hides itself when `<= 1`, so passing the raw size is safe even on standalone items.
    val mergedSourceCount = item.relatedMangaIds.size
    // In selection mode, route tap to the toggle handler (legacy ActionMode behavior).
    val onClick = if (selectionActive) {
        { onMangaLongClick(manga) }
    } else {
        { onMangaClick(manga) }
    }
    val onLongClick = { onMangaLongClick(manga) }
    // Continue-reading button: only when the user has not hidden it AND the manga has unread
    // chapters AND we're not in selection mode (selection mode claims all cell taps). Skip the
    // cover-only layout's button entirely so the cover stays unobstructed.
    val continueReadingClick = if (
        !selectionActive &&
        !hideStartReadingButton &&
        item.libraryManga.unread > 0 &&
        libraryLayout != LAYOUT_COVER_ONLY_GRID
    ) {
        { onContinueReading(manga) }
    } else {
        null
    }
    // Skip per-cover loading indicator: with large libraries each Coil state transition is a
    // recompose, and the cover placeholder color is enough visual cue.
    when (libraryLayout) {
        LAYOUT_COMFORTABLE_GRID -> MangaComfortableGridItem(
            coverData = coverData,
            title = title,
            modifier = modifier,
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            mergedSourceCount = mergedSourceCount,
            isSelected = isSelected,
            showOutline = outlineOnCovers,
            coverAspectRatio = coverAspectRatio,
            unreadDot = unreadDot,
            isLocal = isLocal,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = continueReadingClick,
            showLoadingIndicator = false,
        )
        LAYOUT_COVER_ONLY_GRID -> MangaCompactGridItem(
            coverData = coverData,
            title = title,
            modifier = modifier,
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            mergedSourceCount = mergedSourceCount,
            isSelected = isSelected,
            showOutline = outlineOnCovers,
            showTitle = false,
            coverAspectRatio = coverAspectRatio,
            unreadDot = unreadDot,
            isLocal = isLocal,
            onClick = onClick,
            onLongClick = onLongClick,
            showLoadingIndicator = false,
        )
        // LAYOUT_COMPACT_GRID default; LAYOUT_LIST is rendered by the isList branch upstream.
        else -> MangaCompactGridItem(
            coverData = coverData,
            title = title,
            modifier = modifier,
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            mergedSourceCount = mergedSourceCount,
            isSelected = isSelected,
            showOutline = outlineOnCovers,
            coverAspectRatio = coverAspectRatio,
            unreadDot = unreadDot,
            isLocal = isLocal,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = continueReadingClick,
            showLoadingIndicator = false,
        )
    }
}
