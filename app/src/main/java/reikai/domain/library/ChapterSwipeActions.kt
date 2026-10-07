package reikai.domain.library

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction

/**
 * A chapter row's two swipes in screen terms. Upstream's preference names are crossed on purpose
 * (`swipeToEndAction` holds the start side's action), so every chapter list reads them through here
 * and a swipe direction does the same thing on every surface and both content types.
 */
@Immutable
data class ChapterSwipeActions(
    val start: ChapterSwipeAction,
    val end: ChapterSwipeAction,
) {
    companion object {
        val DISABLED = ChapterSwipeActions(ChapterSwipeAction.Disabled, ChapterSwipeAction.Disabled)
    }
}

fun LibraryPreferences.chapterSwipeActions() =
    ChapterSwipeActions(start = swipeToEndAction.get(), end = swipeToStartAction.get())

fun LibraryPreferences.chapterSwipeActionsChanges(): Flow<ChapterSwipeActions> =
    combine(swipeToEndAction.changes(), swipeToStartAction.changes()) { start, end ->
        ChapterSwipeActions(start = start, end = end)
    }
