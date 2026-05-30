package yokai.presentation.library.manga

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
 * The [modifier] should carry `Modifier.animateItem()` from the caller's `items{}` block scope;
 * scope receivers can't be parameterized polymorphically since [LazyItemScope] (LazyColumn),
 * [androidx.compose.foundation.lazy.grid.LazyGridItemScope], and
 * [androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope] all expose their
 * own `animateItem`. Caller composes the modifier and passes it through.
 */
@Composable
fun MangaLibraryListItem(
    item: LibraryItem.Manga,
    isSelected: Boolean,
    selectionActive: Boolean,
    modifier: Modifier,
    showDownloadBadge: Boolean,
    showLanguageBadge: Boolean,
    unreadBadgeType: Int,
    onMangaClick: (Manga) -> Unit,
    onToggleSelection: (id: Long, categoryId: Int, fromLongPress: Boolean) -> Unit,
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
        // Caller passes Modifier.animateItem() from the items{} scope so row drops/swaps animate
        // when a merge / unmerge / sort change reshapes the list.
        modifier = modifier,
        subtitle = subtitle.takeIf { it.isNotEmpty() },
        isSelected = isSelected,
        onClick = if (selectionActive) {
            { manga.id?.let { onToggleSelection(it, item.libraryManga.category, false) } }
        } else {
            { onMangaClick(manga) }
        },
        onLongClick = { manga.id?.let { onToggleSelection(it, item.libraryManga.category, true) } },
        trailing = if (segments.isNotEmpty()) {
            { Badge(segments = segments) }
        } else {
            null
        },
    )
}
