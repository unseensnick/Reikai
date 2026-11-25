package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelHistory
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelTrackUpdater
import kotlin.time.Instant

/** What binding a tracker to a novel sends the site from the novel's own reading. */
class AddNovelTrackTest {

    private val tracker = mockk<Tracker>(relaxed = true)
    private val updater = mockk<NovelTrackUpdater>(relaxed = true)
    private val chapters = mockk<NovelChapterRepository>()
    private val history = mockk<NovelHistoryRepository>()

    private val saved = mutableListOf<NovelTrack>()
    private val tracks = mockk<NovelTrackRepository> {
        coEvery { upsert(any()) } answers {
            saved += firstArg<NovelTrack>()
            true
        }
    }

    private val addNovelTrack = AddNovelTrack(UpsertNovelTrack(tracks), updater, chapters, history)

    private fun chapter(number: Double, read: Boolean) = NovelChapter(
        id = number.toLong(), novelId = NOVEL_ID, url = "c$number", name = "Chapter $number", read = read,
        bookmark = false, lastTextProgress = 0, chapterNumber = number, sourceOrder = 0,
        dateFetch = Instant.fromEpochMilliseconds(0),
        dateUpload = null, page = "",
    )

    private suspend fun bindAfterReading(vararg read: Boolean) {
        coEvery { chapters.getByNovelId(NOVEL_ID) } returns read.mapIndexed { i, r -> chapter(i + 1.0, r) }
        coEvery { history.getHistoryByNovelId(NOVEL_ID) } returns
            listOf(NovelHistory(1L, Instant.fromEpochMilliseconds(FIRST_READ_AT), 0L))
        addNovelTrack.bind(tracker, Track.create(1L).apply { title = "A novel" }, NOVEL_ID)
    }

    @Test
    fun `the binding is saved on the novel`() = runTest {
        bindAfterReading(false)

        saved.single().novelId shouldBe NOVEL_ID
    }

    // The site makes the entry Reading rather than Planned from this, so it is the novel's own reading
    @Test
    fun `the site is told the novel has chapters read`() = runTest {
        bindAfterReading(false, true)

        coVerify { tracker.bind(any(), true) }
    }

    @Test
    fun `the chapters already read are sent to the site`() = runTest {
        bindAfterReading(true, true, false)

        coVerify { updater.setRemoteLastChapterRead(tracker, any(), 2) }
    }

    @Test
    fun `the day reading began is sent to the site`() = runTest {
        bindAfterReading(true, false)

        coVerify { updater.setRemoteStartDate(tracker, any(), FIRST_READ_AT) }
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
