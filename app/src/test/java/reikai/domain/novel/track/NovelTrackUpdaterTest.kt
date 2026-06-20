package reikai.domain.novel.track

import eu.kanade.tachiyomi.data.database.models.TrackImpl
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.model.NovelTrack
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** Verifies the status/score transitions ported from BaseTracker land in `novel_tracks`. */
class NovelTrackUpdaterTest {

    private val reading = 2L
    private val rereading = 5L
    private val completed = 3L

    private val tracker = mockk<Tracker>(relaxed = true) {
        every { getReadingStatus() } returns reading
        every { getRereadingStatus() } returns rereading
        every { getCompletionStatus() } returns completed
        coEvery { update(any(), any()) } answers { firstArg() }
    }

    private val inserted = mutableListOf<NovelTrack>()
    private val repository = mockk<NovelTrackRepository> {
        coEvery { insert(any()) } answers { inserted += firstArg<NovelTrack>() }
    }
    private val updater = NovelTrackUpdater(InsertNovelTrack(repository))

    private fun dbTrack(lastRead: Double = 0.0, total: Long = 0, status: Long = 0): DbTrack =
        TrackImpl().also {
            it.id = 1L
            it.manga_id = 42L
            it.tracker_id = 2L
            it.title = "t"
            it.last_chapter_read = lastRead
            it.total_chapters = total
            it.status = status
        }

    @Test
    fun `first progress flips an unread track to reading`() = runTest {
        updater.setRemoteLastChapterRead(tracker, dbTrack(lastRead = 0.0, total = 10), 3)
        inserted.single().let {
            it.lastChapterRead shouldBe 3.0
            it.status shouldBe reading
        }
    }

    @Test
    fun `reaching the total marks completed and stamps a finish date`() = runTest {
        updater.setRemoteLastChapterRead(tracker, dbTrack(lastRead = 9.0, total = 10, status = reading), 10)
        inserted.single().let {
            it.status shouldBe completed
            (it.finishDate > 0L) shouldBe true
        }
    }

    @Test
    fun `setting completed status snaps last chapter to the total`() = runTest {
        updater.setRemoteStatus(tracker, dbTrack(lastRead = 4.0, total = 10), completed)
        inserted.single().lastChapterRead shouldBe 10.0
    }
}
