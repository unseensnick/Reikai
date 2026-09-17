package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.track.NovelTrackUpdater
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** Binding a tracker to a novel already read gives the tracker the date it was first read, as manga does. */
class AddNovelTrackTest {

    private val tracker = mockk<Tracker> { coEvery { bind(any(), any()) } answers { firstArg() } }
    private val updater = mockk<NovelTrackUpdater>(relaxed = true)
    private val chapters = mockk<NovelChapterRepository>()
    private val history = mockk<NovelHistoryRepository>()
    private val addNovelTrack = AddNovelTrack(mockk(relaxed = true), updater, chapters, history)

    @Test
    fun `a read novel bound to a tracker without a start date gets its first read`() = runTest {
        coEvery { chapters.getByNovelId(NOVEL_ID) } returns listOf(chapter(read = true))
        coEvery { history.getEarliestReadAt(NOVEL_ID) } returns FIRST_READ_AT

        addNovelTrack.bind(tracker, remoteTrack(startDate = 0L), NOVEL_ID)

        val expected = FIRST_READ_AT.convertEpochMillisZone(TimeZone.currentSystemDefault(), TimeZone.UTC)
        coVerify { updater.setRemoteStartDate(tracker, any(), expected) }
    }

    @Test
    fun `a start date the tracker already has is kept`() = runTest {
        coEvery { chapters.getByNovelId(NOVEL_ID) } returns listOf(chapter(read = true))
        coEvery { history.getEarliestReadAt(NOVEL_ID) } returns FIRST_READ_AT

        addNovelTrack.bind(tracker, remoteTrack(startDate = 1_000L), NOVEL_ID)

        coVerify(exactly = 0) { updater.setRemoteStartDate(any(), any(), any()) }
    }

    @Test
    fun `a novel with nothing read sets no start date`() = runTest {
        coEvery { chapters.getByNovelId(NOVEL_ID) } returns listOf(chapter(read = false))
        coEvery { history.getEarliestReadAt(NOVEL_ID) } returns null

        addNovelTrack.bind(tracker, remoteTrack(startDate = 0L), NOVEL_ID)

        coVerify(exactly = 0) { updater.setRemoteStartDate(any(), any(), any()) }
    }

    private fun remoteTrack(startDate: Long) = DbTrack.create(TRACKER_ID).apply {
        remote_id = 100L
        title = "A novel"
        started_reading_date = startDate
    }

    private fun chapter(read: Boolean) = NovelChapter(
        id = 1L,
        novelId = NOVEL_ID,
        url = "/c1",
        name = "Chapter 1",
        read = read,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = 1.0,
        sourceOrder = 0L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private companion object {
        const val NOVEL_ID = 7L
        const val TRACKER_ID = 2L
        const val FIRST_READ_AT = 1_700_000_000_000L
    }
}
