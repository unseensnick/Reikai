package reikai.presentation.details

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch

/** The chapter list's Scroll to top / next unread / bottom, one set for the one- and two-pane layouts. */
@Immutable
class ChapterJumps(
    val toTop: () -> Unit,
    val toNextUnread: () -> Unit,
    val toBottom: () -> Unit,
)

/**
 * Jumps over [listState], whose last [rows].size items must be the chapter rows: what comes before them
 * (info, chips, carousel, chapter header) varies, so its count is derived rather than named. Next unread
 * is [nextUnreadId], the chapter the Start/Resume button opens; one [rows] does not hold (filtered out,
 * another volume or page) calls [onNoTarget] as no unread chapter does.
 */
@Composable
internal fun rememberChapterJumps(
    listState: LazyListState,
    rows: List<EntryChapterListItem>,
    nextUnreadId: Long?,
    onNoTarget: () -> Unit,
): ChapterJumps {
    val scope = rememberCoroutineScope()
    val currentRows by rememberUpdatedState(rows)
    val currentTarget by rememberUpdatedState(nextUnreadId)
    val currentOnNoTarget by rememberUpdatedState(onNoTarget)
    return remember(listState, scope) {
        ChapterJumps(
            toTop = { scope.launch { listState.animateScrollToItem(0) } },
            toNextUnread = {
                val target = currentTarget
                val index = currentRows.indexOfFirst { it is EntryChapterListItem.Chapter && it.id == target }
                if (index < 0) {
                    currentOnNoTarget()
                } else {
                    scope.launch {
                        val headerItems = listState.layoutInfo.totalItemsCount - currentRows.size
                        // An item at offset 0 sits below the top padding (the two-pane list's app bar).
                        val toMiddle = with(listState.layoutInfo) { viewportSize.height / 2 - beforeContentPadding }
                        listState.animateScrollToItem(headerItems + index, scrollOffset = -toMiddle)
                    }
                }
            },
            toBottom = {
                scope.launch {
                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            },
        )
    }
}
