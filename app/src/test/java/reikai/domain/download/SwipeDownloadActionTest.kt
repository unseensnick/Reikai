package reikai.domain.download

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * What a Download swipe does at each state the indicator can be in. Pinned once because every Reikai
 * row runs this one function: Recents, the novel details list and both readers' chapter sheets.
 */
class SwipeDownloadActionTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "a chapter that is not downloaded downloads now, NOT_DOWNLOADED, START_NOW",
        "a failed download is retried rather than cleared, ERROR, START_NOW",
        "a queued download is cancelled, QUEUE, CANCEL",
        "a running download is cancelled, DOWNLOADING, CANCEL",
        "a finished download is deleted, DOWNLOADED, DELETE",
    )
    fun `a swipe acts on the state the indicator shows`(
        name: String,
        state: Download.State,
        expected: ChapterDownloadAction,
    ) {
        state.swipeDownloadAction() shouldBe expected
    }
}
