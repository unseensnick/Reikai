package reikai.presentation.recents

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What a Download swipe does at each state the indicator can be in. Pinned once because both content
 * types run this one function; the two details models each still carry their own copy of the rule.
 */
class RecentsSwipeTest {

    @Test
    fun `a chapter that is not downloaded downloads now`() {
        swipeDownloadAction(Download.State.NOT_DOWNLOADED) shouldBe ChapterDownloadAction.START_NOW
    }

    @Test
    fun `a failed download is retried rather than cleared`() {
        swipeDownloadAction(Download.State.ERROR) shouldBe ChapterDownloadAction.START_NOW
    }

    @Test
    fun `a queued download is cancelled`() {
        swipeDownloadAction(Download.State.QUEUE) shouldBe ChapterDownloadAction.CANCEL
    }

    @Test
    fun `a running download is cancelled`() {
        swipeDownloadAction(Download.State.DOWNLOADING) shouldBe ChapterDownloadAction.CANCEL
    }

    @Test
    fun `a finished download is deleted`() {
        swipeDownloadAction(Download.State.DOWNLOADED) shouldBe ChapterDownloadAction.DELETE
    }
}
