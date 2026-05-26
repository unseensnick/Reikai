package yokai.presentation.library.novels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import yokai.domain.manga.models.MangaCover as MangaCoverModel
import yokai.domain.novel.models.Novel
import yokai.presentation.manga.components.Badge
import yokai.presentation.manga.components.BadgeSegments
import yokai.presentation.manga.components.MangaListItem

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryListItem] (Phase 8 C8).
 * Renders one list row for [LibraryItem.Novel] via the same generic [MangaListItem] primitive
 * the manga side uses (which accepts cover data, title, subtitle, callbacks, and an optional
 * trailing slot — no manga coupling at the parameter level).
 *
 * Cover routing mirrors [NovelLibraryGridCell]: builds a [MangaCoverModel] with `sourceId = 0L`
 * so Coil doesn't attempt manga-source HTTP routing.
 */
@Composable
fun NovelLibraryListItem(
    item: LibraryItem.Novel,
    isSelected: Boolean,
    selectionActive: Boolean,
    modifier: Modifier,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    onNovelClick: (Novel) -> Unit,
    onToggleSelection: (Long) -> Unit,
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
    val subtitle = remember(novel.id) {
        val author = novel.author?.trim().orEmpty()
        val artist = novel.artist?.trim().orEmpty()
        when {
            author.isEmpty() -> artist
            artist.isEmpty() || artist == author -> author
            author.contains(artist, true) -> author
            else -> "$author, $artist"
        }
    }
    val unreadCount = if (unreadBadgeType > 0) item.libraryNovel.unread else 0
    val unreadDot = unreadBadgeType == 1
    val downloadCount = if (showDownloadBadge) {
        item.downloadCount.toInt().coerceAtLeast(0)
    } else {
        0
    }
    val lang = if (showLanguageBadge) item.language.takeIf { it.isNotBlank() } else null
    val segments = BadgeSegments(
        lang = lang,
        unreadCount = unreadCount,
        downloadCount = downloadCount,
        unreadDot = unreadDot,
        isLocal = false,
    )
    MangaListItem(
        coverData = coverData,
        title = title,
        modifier = modifier,
        subtitle = subtitle.takeIf { it.isNotEmpty() },
        isSelected = isSelected,
        onClick = if (selectionActive) {
            { novel.id?.let(onToggleSelection) }
        } else {
            { onNovelClick(novel) }
        },
        onLongClick = { novel.id?.let(onToggleSelection) },
        trailing = if (segments.isNotEmpty()) {
            { Badge(segments = segments) }
        } else {
            null
        },
    )
}
