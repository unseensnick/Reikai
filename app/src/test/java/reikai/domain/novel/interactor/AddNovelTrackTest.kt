package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.database.models.Track
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

/** What binding a tracker to a novel sends the site from the novel's own reading. */
class AddNovelTrackTest {

    private val tracker = mockk<Tracker>(relaxed = true)
    private val updater = mockk<NovelTrackUpdater>(relaxed = true)
    private val chapters = mockk<NovelChapterRepository>()
    private val history = mockk<NovelHistoryRepository>()

    private val addNovelTrack = AddNovelTrack(mockk(relaxed = true), updater, chapters, history)

    private fun chapter(number: Double, read: Boolean) = NovelChapter(
        id = number.toLong(), novelId = NOVEL_ID, url = "c$number", name = "Chapter $number", read = read,
        bookmark = false, lastTextProgress = 0, chapterNumber = number, sourceOrder = 0, dateFetch = 0,
        dateUpload = 0, page = "",
    )

    private suspend fun bindAfterReading(vararg read: Boolean) {
        coEvery { chapters.getByNovelId(NOVEL_ID) } returns read.mapIndexed { i, r -> chapter(i + 1.0, r) }
        coEvery { history.getEarliestReadAt(NOVEL_ID) } returns FIRST_READ_AT
        addNovelTrack.bind(tracker, Track.create(1L).apply { title = "A novel" }, NOVEL_ID)
    }

    @Test
    fun `the chapters already read are sent to the site`() = runTest {
        bindAfterReading(true, true, false)

        coVerify { updater.setRemoteLastChapterRead(tracker, any(), 2) }
    }

    @Test
    fun `the day reading began is sent to the site`() = runTest {
        bindAfterReading(true, false)

        val firstDay = FIRST_READ_AT.convertEpochMillisZone(TimeZone.currentSystemDefault(), TimeZone.UTC)

        coVerify { updater.setRemoteStartDate(tracker, any(), firstDay) }
    }

    @Test
    fun `nothing read sends the site nothing`() = runTest {
        bindAfterReading(false, false)

        coVerify(exactly = 0) { updater.setRemoteLastChapterRead(tracker, any(), any()) }
    }

    private companion object {
        const val NOVEL_ID = 7L
        const val FIRST_READ_AT = 1_700_000_000_000L
    }
}
