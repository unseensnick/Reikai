package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** MangaTrackWriter forwards each write straight to the tracker (BaseTracker persists to manga_sync). */
class MangaTrackWriterTest {

    private val track = mockk<DbTrack>()
    private val tracker = mockk<Tracker>(relaxed = true)

    @Test
    fun `setRemoteStatus forwards to the tracker`() = runTest {
        MangaTrackWriter.setRemoteStatus(tracker, track, 3L)
        coVerify { tracker.setRemoteStatus(track, 3L) }
    }

    @Test
    fun `setRemoteLastChapterRead forwards to the tracker`() = runTest {
        MangaTrackWriter.setRemoteLastChapterRead(tracker, track, 12)
        coVerify { tracker.setRemoteLastChapterRead(track, 12) }
    }

    @Test
    fun `setRemoteScore forwards to the tracker`() = runTest {
        MangaTrackWriter.setRemoteScore(tracker, track, "8")
        coVerify { tracker.setRemoteScore(track, "8") }
    }

    @Test
    fun `setRemoteStartDate forwards to the tracker`() = runTest {
        MangaTrackWriter.setRemoteStartDate(tracker, track, 1000L)
        coVerify { tracker.setRemoteStartDate(track, 1000L) }
    }

    @Test
    fun `setRemoteFinishDate forwards to the tracker`() = runTest {
        MangaTrackWriter.setRemoteFinishDate(tracker, track, 2000L)
        coVerify { tracker.setRemoteFinishDate(track, 2000L) }
    }

    @Test
    fun `setRemotePrivate forwards to the tracker`() = runTest {
        MangaTrackWriter.setRemotePrivate(tracker, track, true)
        coVerify { tracker.setRemotePrivate(track, true) }
    }

    @Test
    fun `trackWriterFor picks the manga writer for manga`() {
        trackWriterFor(isNovel = false) shouldBe MangaTrackWriter
    }
}
