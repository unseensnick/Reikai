package yokai.presentation.library.novels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COMFORTABLE_GRID
import eu.kanade.tachiyomi.ui.library.LibraryItem.Companion.LAYOUT_COVER_ONLY_GRID
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import yokai.domain.manga.models.MangaCover as MangaCoverModel
import yokai.domain.novel.models.Novel
import yokai.presentation.manga.components.MangaComfortableGridItem
import yokai.presentation.manga.components.MangaCompactGridItem
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryGridCell] (Phase 8 C8).
 * Resolves the badge / cover prefs into the right [MangaComfortableGridItem] /
 * [MangaCompactGridItem] call.
 *
 * Constructs a [MangaCoverModel] from the novel's thumbnail with `sourceId = 0L` (novels don't
 * route through manga-source Coil fetchers; the URL is loaded directly the same way the Phase 7
 * debug screen at `NovelLibraryScreen.kt:106` does it). [mangaId] takes the novel's id for the
 * shared cover-ratio cache; collisions with manga ids are best-effort and unproblematic since
 * the cache is a soft hint only.
 *
 * [isLocal] is hardcoded false (no novel local-source equivalent in Phase 7) and
 * [onClickContinueReading] is null (per Phase 7 Decision #4, novel downloads + continue-reading
 * are deferred). The cell helper hides the continue-reading button when null.
 */
@Composable
fun NovelLibraryGridCell(
    item: LibraryItem.Novel,
    libraryLayout: Int,
    outlineOnCovers: Boolean,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    /** True when at least one library item is currently selected (matches manga parity). */
    selectionActive: Boolean,
    onNovelClick: (Novel) -> Unit,
    onNovelLongClick: (Novel) -> Unit,
    /**
     * Cover aspect ratio. Default 2:3 (book) for the regular grid path; pass `null` for the
     * staggered grid so each cover renders at its image's intrinsic ratio.
     */
    coverAspectRatio: Float? = MangaCoverRatio.BOOK,
) {
    val novel = item.libraryNovel.novel
    val coverData = remember(novel.id) {
        MangaCoverModel(
            mangaId = novel.id,
            sourceId = 0L,
            url = novel.thumbnailUrl ?: "",
            lastModified = novel.coverLastModified,
            inLibrary = novel.favorite,
        )
    }
    val title = remember(novel.id) { novel.title }
    val unreadCount = if (unreadBadgeType > 0) item.libraryNovel.unread else 0
    val unreadDot = unreadBadgeType == 1
    val downloadCount = if (showDownloadBadge) {
        item.downloadCount.toInt().coerceAtLeast(0)
    } else {
        0
    }
    val lang = if (showLanguageBadge) item.language.takeIf { it.isNotBlank() } else null
    val mergedSourceCount = item.relatedNovelIds.size
    val onClick = if (selectionActive) {
        { onNovelLongClick(novel) }
    } else {
        { onNovelClick(novel) }
    }
    val onLongClick = { onNovelLongClick(novel) }
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
            isLocal = false,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = null,
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
            isLocal = false,
            onClick = onClick,
            onLongClick = onLongClick,
            showLoadingIndicator = false,
        )
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
            isLocal = false,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickContinueReading = null,
            showLoadingIndicator = false,
        )
    }
}
