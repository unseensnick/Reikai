package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition

class TransitionDownloadState(
    val currChapterDownloaded: Boolean,
    val goingToChapterDownloaded: Boolean, // RK: known at bind, with no later disk probe to wait for
)

// RK --> the chapter's own merged source, from the in-memory download cache, so binding stays cheap
// and the badge is right the first time rather than after a storage probe reflows the page
fun ReaderViewModel.transitionDownloadState(transition: ChapterTransition) = TransitionDownloadState(
    currChapterDownloaded = transition.from.pageLoader?.isLocal == true,
    goingToChapterDownloaded = isChapterDownloaded(transition.to),
)
// RK <--
