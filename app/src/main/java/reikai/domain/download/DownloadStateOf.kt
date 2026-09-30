package reikai.domain.download

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download

/**
 * A chapter's download state as every Reikai row reads it, for both content types: a queued
 * download's own state wins, then the on-disk index. [isOnDisk] runs only when nothing is queued,
 * since reading the index is the costly half. Mihon's own rows keep their copy in upstream shape.
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
