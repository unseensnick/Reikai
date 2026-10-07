package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.download.swipeDownloadAction
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction

/**
 * Runs one row's swipe. Each verb acts on that row alone and leaves the selection alone, which is why
 * these are the engine's per-row verbs and not the selection ones.
 */
internal fun RecentsEngine.runChapterSwipe(
    ref: ChapterRef,
    lane: RecentsLane,
    state: RecentsChapterState,
    downloadState: () -> Download.State,
    action: ChapterSwipeAction,
) {
    val refs = setOf(ref)
    when (action) {
        ChapterSwipeAction.ToggleRead -> markRead(refs, !state.read)
        ChapterSwipeAction.ToggleBookmark -> setBookmark(refs, !state.bookmark)
        ChapterSwipeAction.Download -> download(refs, downloadState().swipeDownloadAction(), lane)
        // Unreachable rather than unhandled: getSwipeAction draws no gesture for it, so nothing can
        // raise it here. Upstream throws instead, which would put a crash behind an absent gesture.
        ChapterSwipeAction.Disabled -> Unit
    }
}
