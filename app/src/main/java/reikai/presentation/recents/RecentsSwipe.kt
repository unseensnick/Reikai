package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction

/**
 * The two swipe choices a row draws, in screen terms rather than preference terms: the preference
 * names are crossed (`swipeToEndAction` holds the start-side action), so the engine resolves them
 * once and nothing downstream has to remember the inversion.
 */
@Immutable
data class RecentsSwipeActions(
    val start: ChapterSwipeAction,
    val end: ChapterSwipeAction,
)

/**
 * What a Download swipe does, decided by what the row's indicator is already showing. One definition
 * for both content types, where the two details models each carry their own copy of this mapping.
 */
fun swipeDownloadAction(state: Download.State): ChapterDownloadAction = when (state) {
    Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START_NOW
    Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
}

/**
 * Runs one row's swipe. Each verb acts on that row alone and leaves the selection alone, which is why
 * these are the engine's per-row verbs and not the selection ones.
 */
internal fun RecentsEngine.runChapterSwipe(
    ref: ChapterRef,
    chapter: RecentsChapterUi.Named,
    downloadState: () -> Download.State,
    action: ChapterSwipeAction,
) {
    val refs = setOf(ref)
    when (action) {
        ChapterSwipeAction.ToggleRead -> markRead(refs, !chapter.read)
        ChapterSwipeAction.ToggleBookmark -> setBookmark(refs, !chapter.bookmark)
        ChapterSwipeAction.Download -> download(refs, swipeDownloadAction(downloadState()))
        // Unreachable rather than unhandled: getSwipeAction draws no gesture for it, so nothing can
        // raise it here. Upstream throws instead, which would put a crash behind an absent gesture.
        ChapterSwipeAction.Disabled -> Unit
    }
}
