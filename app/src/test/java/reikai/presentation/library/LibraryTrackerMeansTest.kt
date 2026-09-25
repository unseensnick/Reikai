package reikai.presentation.library

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track

class LibraryTrackerMeansTest {

    private fun track(entryId: Long, trackerId: Long, score: Double) = Track(
        id = entryId * 10 + trackerId,
        mangaId = entryId,
        trackerId = trackerId,
        remoteId = 0L,
        libraryId = null,
        title = "",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = score,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun tracker(id: Long) = mockk<Tracker> {
        every { this@mockk.id } returns id
        every { get10PointScore(any()) } answers { firstArg<Track>().score }
    }

    @Test
    fun `a group scores once per logged-in tracker and skips unrated scores`() {
        // Members 1 and 2 are one group. Tracker 1 is bound on both (counted once, the first member's
        // score), tracker 2 is logged out, tracker 3 is bound but unrated.
        val means = libraryTrackerMeans(
            membersByRow = mapOf(1L to listOf(1L, 2L)),
            tracksById = mapOf(
                1L to listOf(track(1L, 1L, 8.0), track(1L, 2L, 2.0)),
                2L to listOf(track(2L, 1L, 4.0), track(2L, 3L, 0.0), track(2L, 4L, 6.0)),
            ),
            trackers = listOf(1L, 3L, 4L).associateWith(::tracker),
        )

        means shouldBe mapOf(1L to 7.0)
    }

    @Test
    fun `a row with no usable score is absent`() {
        val means = libraryTrackerMeans(
            membersByRow = mapOf(1L to listOf(1L)),
            tracksById = mapOf(1L to listOf(track(1L, 2L, 9.0))),
            trackers = emptyMap(),
        )

        means shouldBe emptyMap()
    }
}
