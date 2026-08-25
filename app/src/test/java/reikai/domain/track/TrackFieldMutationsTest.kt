package reikai.domain.track

import eu.kanade.test.DummyTracker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Guards the completion rule these mutations carry, because a tracker whose total counts something
 * other than chapters trips it. RanobeDB counts volumes, so putting its total here would finish a
 * 583-chapter series at chapter 15; it leaves the total at zero instead, and this pins what that
 * buys.
 */
class TrackFieldMutationsTest {

    private val tracker = DummyTracker(id = 1L, name = "Dummy", valCompletionStatus = COMPLETED)

    private fun trackWithTotal(total: Long) = DbTrack.create(1L).apply {
        id = 1L
        title = "A novel"
        total_chapters = total
        status = PLAN_TO_READ
    }

    @Test
    fun `reaching a known total completes the entry`() {
        val track = trackWithTotal(total = 15L)

        TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber = 15)

        track.status shouldBe COMPLETED
    }

    @Test
    fun `an unknown total never completes the entry`() {
        val track = trackWithTotal(total = 0L)

        TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber = 15)

        track.status shouldBe READING
    }

    @Test
    fun `an unknown total still records the chapters read`() {
        val track = trackWithTotal(total = 0L)

        TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber = 574)

        track.last_chapter_read shouldBe 574.0
    }

    @Test
    fun `an unknown total leaves the finish date unstamped`() {
        val track = trackWithTotal(total = 0L)

        TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber = 15)

        track.finished_reading_date shouldBe 0L
    }

    private companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val PLAN_TO_READ = 5L
    }
}
