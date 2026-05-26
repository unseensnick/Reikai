package yokai.presentation.library.manga

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.isLocal
import yokai.domain.manga.models.cover
import yokai.presentation.manga.components.Badge
import yokai.presentation.manga.components.BadgeSegments
import yokai.presentation.manga.components.MangaListItem

/**
 * List-row rendering for a single [LibraryItem.Manga]. Extracted from `LibraryContent.kt` in
 * Phase 8 C7b so the surrounding composable can be type-parameterized over `T : LibraryItem` in
 * C7c without dragging in manga-specific imports. The novel-side parallel is
 * [yokai.presentation.library.novels.NovelLibraryListItem] (Phase 8 C8).
 *
 * Caller (`LibraryContent`'s list branch) provides this as the `listItemRenderer` lambda body;
 * the LazyItemScope receiver is required by [Modifier.animateItem] which smooths row drops /
 * swaps when a merge / unmerge / sort change reshapes the list.
 */
@Composable
fun LazyItemScope.MangaLibraryListItem(
    item: LibraryItem.Manga,
    selection: Set<Long>,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    onMangaClick: (Manga) -> Unit,
    onToggleSelection: (Long) -> Unit,
) {
    val manga = item.libraryManga.manga
    val coverData = remember(manga.id) { manga.cover() }
    val title = remember(manga.id) { manga.title }
    val subtitle = remember(manga.id) {
        val author = manga.author?.trim().orEmpty()
        val artist = manga.artist?.trim().orEmpty()
        when {
            author.isEmpty() -> artist
            artist.isEmpty() || artist == author -> author
            author.contains(artist, true) -> author
            else -> "$author, $artist"
        }
    }
    // Same badge inputs as the grid; legacy list rows render the unread / download chips on the
    // trailing side, mirroring manga_list_item.xml. The Badge component itself is reused so
    // visuals match the grid badges.
    // unreadBadgeType matches the legacy RadioGroup binding (see LibraryHolder.setUnreadBadge):
    // 0/negative = hide, 1 = dot, 2 = count.
    val unreadCount = if (unreadBadgeType > 0) item.libraryManga.unread else 0
    val unreadDot = unreadBadgeType == 1
    val downloadCount = if (showDownloadBadge) {
        item.downloadCount.toInt().coerceAtLeast(0)
    } else {
        0
    }
    val lang = if (showLanguageBadge) item.language.takeIf { it.isNotBlank() } else null
    val isLocal = remember(manga.id) { manga.isLocal() }
    val segments = BadgeSegments(
        lang = lang,
        unreadCount = unreadCount,
        downloadCount = downloadCount,
        unreadDot = unreadDot,
        isLocal = isLocal,
    )
    MangaListItem(
        coverData = coverData,
        title = title,
        // animateItem keeps row drops/swaps smooth instead of pop-jumping when a merge / unmerge
        // / sort change reshapes the list. Safe to apply unconditionally: it only runs when the
        // lazy column actually relocates a row.
        modifier = Modifier.animateItem(),
        subtitle = subtitle.takeIf { it.isNotEmpty() },
        isSelected = manga.id != null && manga.id in selection,
        onClick = if (selection.isNotEmpty()) {
            { manga.id?.let(onToggleSelection) }
        } else {
            { onMangaClick(manga) }
        },
        onLongClick = { manga.id?.let(onToggleSelection) },
        trailing = if (segments.isNotEmpty()) {
            { Badge(segments = segments) }
        } else {
            null
        },
    )
}
