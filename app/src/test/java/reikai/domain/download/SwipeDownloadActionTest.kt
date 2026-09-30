package reikai.domain.download

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What a Download swipe does at each state the indicator can be in. Pinned once because every Reikai
 * row runs this one function: Recents, the novel details list and both readers' chapter sheets.
 */
class SwipeDownloadActionTest {

    @Test
    fun `a chapter that is not downloaded downloads now`() {
        Download.State.NOT_DOWNLOADED.swipeDownloadAction() shouldBe ChapterDownloadAction.START_NOW
    }

    @Test
    fun `a failed download is retried rather than cleared`() {
        Download.State.ERROR.swipeDownloadAction() shouldBe ChapterDownloadAction.START_NOW
    }

    @Test
    fun `a queued download is cancelled`() {
        Download.State.QUEUE.swipeDownloadAction() shouldBe ChapterDownloadAction.CANCEL
    }

    @Test
    fun `a running download is cancelled`() {
        Download.State.DOWNLOADING.swipeDownloadAction() shouldBe ChapterDownloadAction.CANCEL
    }

    @Test
    fun `a finished download is deleted`() {
        Download.State.DOWNLOADED.swipeDownloadAction() shouldBe ChapterDownloadAction.DELETE
    }
}
