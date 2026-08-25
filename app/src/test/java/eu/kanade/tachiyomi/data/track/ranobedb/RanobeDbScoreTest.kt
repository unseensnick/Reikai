package eu.kanade.tachiyomi.data.track.ranobedb

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * A search result arrives with score -1 as its unset marker, so a freshly bound entry rendered
 * "-1" where it should read as unscored. RanobeDB's own range starts at 1, so nothing at or below
 * zero is a real score.
 */
class RanobeDbScoreTest {

    private val tracker = RanobeDb(102L)

    private fun trackScoring(score: Double) = DomainTrack(
        id = 1L,
        mangaId = 2L,
        trackerId = 102L,
        remoteId = 3L,
        libraryId = null,
        title = "A novel",
        lastChapterRead = 0.0,
        totalChapters = 15L,
        status = 5L,
        score = score,
        remoteUrl = "https://ranobedb.org/series/3",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    @Test
    fun `an unset search result score reads as unscored`() {
        tracker.displayScore(trackScoring(-1.0)) shouldBe "-"
    }

    @Test
    fun `a zero score reads as unscored`() {
        tracker.displayScore(trackScoring(0.0)) shouldBe "-"
    }

    @Test
    fun `a real score reads as a whole number`() {
        tracker.displayScore(trackScoring(8.0)) shouldBe "8"
    }
}
