package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * The shared kernel both chapter interactors call, so the "persist what the push returned" rule is
 * pinned once rather than twice. Before this existed, the manga and novel interactors each wrote
 * back the pre-push row and left the local status reading "plan to read" while the service had
 * already moved on.
 */
class PushChapterProgressTest {

    private val tracker = mockk<Tracker>()

    private fun localRow() = DbTrack.create(TRACKER_ID).apply {
        id = LOCAL_ID
        manga_id = 7L
        remote_id = 100L
        title = "A novel"
        last_chapter_read = 5.0
        total_chapters = 13L
        status = PLAN_TO_READ
        score = 0.0
        tracking_url = "https://example.test/series/100"
    }

    @Test
    fun `keeps the status a tracker set on the row it was handed`() = runTest {
        coEvery { tracker.update(any(), any()) } answers {
            firstArg<DbTrack>().apply {
                status = READING
                started_reading_date = STARTED_AT
            }
        }

        tracker.pushChapterProgress(localRow()).status shouldBe READING
    }

    @Test
    fun `takes the status from a tracker that answers with a different row`() = runTest {
        coEvery { tracker.update(any(), any()) } returns remoteAnswer()

        tracker.pushChapterProgress(localRow()).status shouldBe READING
    }

    @Test
    fun `keeps the local id when the tracker answers with a row that has none`() = runTest {
        // Kavita, Komga and Suwayomi re-fetch and return a TrackSearch, whose id is always null.
        // Losing it here would make the row unpersistable.
        coEvery { tracker.update(any(), any()) } returns remoteAnswer()

        tracker.pushChapterProgress(localRow()).id shouldBe LOCAL_ID
    }

    @Test
    fun `takes the start date a tracker stamps on a different row`() = runTest {
        coEvery { tracker.update(any(), any()) } returns remoteAnswer()

        tracker.pushChapterProgress(localRow()).started_reading_date shouldBe STARTED_AT
    }

    private fun remoteAnswer(): TrackSearch = TrackSearch.create(TRACKER_ID).apply {
        title = "A novel"
        tracking_url = "https://example.test/series/100"
        remote_id = 100L
        last_chapter_read = 6.0
        status = READING
        started_reading_date = STARTED_AT
    }

    private companion object {
        const val TRACKER_ID = 102L
        const val LOCAL_ID = 42L
        const val PLAN_TO_READ = 5L
        const val READING = 1L
        const val STARTED_AT = 1_767_225_600_000L
    }
}
