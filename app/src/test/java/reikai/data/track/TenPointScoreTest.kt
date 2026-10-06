package reikai.data.track

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.track.model.Track as DomainTrack

class ScoreCase(private val label: String, val tracker: () -> BaseTracker) {
    override fun toString() = label
}

/**
 * The two light-novel trackers that score 1..10. A search result arrives with score -1 as its unset
 * marker, so a freshly bound entry rendered "-1" where it should read as unscored.
 */
class TenPointScoreTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `an unset search result score reads as unscored`(case: ScoreCase) {
        case.tracker().displayScore(trackScoring(-1.0)) shouldBe "-"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a zero score reads as unscored`(case: ScoreCase) {
        case.tracker().displayScore(trackScoring(0.0)) shouldBe "-"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a real score reads as a whole number`(case: ScoreCase) {
        case.tracker().displayScore(trackScoring(8.0)) shouldBe "8"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the picker offers unset then one to ten`(case: ScoreCase) {
        case.tracker().getScoreList() shouldBe listOf("-", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
    }

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
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    companion object {
        @JvmStatic
        fun cases() = listOf(
            ScoreCase("RanobeDB") { RanobeDb(102L) },
            ScoreCase("NovelList") { NovelList(101L) },
        )
    }
}
