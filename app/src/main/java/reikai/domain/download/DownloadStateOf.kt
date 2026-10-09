package reikai.domain.download

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.merge.DownloadTargets
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction

/**
 * A chapter's download state as every row Reikai draws reads it, for both content types: a queued
 * download's own state wins, then the on-disk index. [isOnDisk] runs only when nothing is queued,
 * since reading the index is the costly half.
 */
inline fun downloadStateOf(queued: Download.State?, isOnDisk: () -> Boolean): Download.State = when {
    queued != null -> queued
    isOnDisk() -> Download.State.DOWNLOADED
    else -> Download.State.NOT_DOWNLOADED
}

/**
 * What a Download swipe does, decided by what the row's indicator is already showing, for every
 * Reikai row of both content types. MangaViewModel keeps upstream's copy of this mapping.
 */
fun Download.State.swipeDownloadAction(): ChapterDownloadAction = when (this) {
    Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START_NOW
    Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
}

/**
 * Whether a row in [downloadState] draws its download control and takes a download swipe, for every
 * Reikai row of both content types: one on disk or queued always does, an idle one only when its
 * download can fetch a copy, so a chapter only an uninstalled source holds offers no control that
 * would do nothing. [canFetch] answers for the copy a download fetches, for a caller whose targets
 * were not built knowing which sources are installed.
 */
fun DownloadTargets.offersDownload(
    chapterId: Long,
    downloadState: Download.State,
    canFetch: (copyId: Long) -> Boolean = { true },
): Boolean = downloadState != Download.State.NOT_DOWNLOADED || idOf(chapterId)?.let(canFetch) == true

/** This swipe as a row that offers no download takes it: a Download swipe goes with the control. */
fun ChapterSwipeAction.whereDownloadOffered(offersDownload: Boolean): ChapterSwipeAction =
    if (offersDownload || this != ChapterSwipeAction.Download) this else ChapterSwipeAction.Disabled
