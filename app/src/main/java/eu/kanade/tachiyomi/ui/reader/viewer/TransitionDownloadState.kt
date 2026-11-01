package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition

class TransitionDownloadState(
    val currChapterDownloaded: Boolean,
    goingToChapterDownloaded: Boolean,
    private val isGoingToChapterOnDisk: (suspend () -> Boolean)?,
) {
    var goingToChapterDownloaded by mutableStateOf(goingToChapterDownloaded)
        private set

    suspend fun checkDisk() {
        isGoingToChapterOnDisk?.let { goingToChapterDownloaded = it() }
    }
}

// RK --> the chapter's own merged source, from the in-memory download cache, so binding stays cheap
// and the badge is right the first time rather than after a storage probe reflows the page
fun ReaderViewModel.transitionDownloadState(transition: ChapterTransition) = TransitionDownloadState(
    currChapterDownloaded = transition.from.pageLoader?.isLocal == true,
    goingToChapterDownloaded = isChapterDownloaded(transition.to),
    isGoingToChapterOnDisk = null,
)
// RK <--
