package eu.kanade.domain.track.interactor

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.track.model.Track

/** What a manga server's reading progress does to the local chapters when a tracker binds or refreshes. */
class SyncChapterProgressWithTrackTest {

    private var marked: List<ChapterUpdate>? = null

    private fun sync(readLocally: Set<Double> = emptySet()) = SyncChapterProgressWithTrack(
        updateChapter = mockk<UpdateChapter> { coEvery { awaitAll(any()) } answers { marked = firstArg() } },
        insertTrack = mockk(relaxed = true),
        getChaptersByMangaId = mockk<GetChaptersByMangaId> {
            coEvery { await(any()) } returns listOf(0.0, 1.0, 2.0, 3.0).map { number ->
                Chapter.create().copy(
                    id = (number * 10).toLong(),
                    mangaId = 1L,
                    chapterNumber = number,
                    read = number in readLocally,
                )
            }
        },
    )

    private val server = mockk<Tracker>(relaxed = true, moreInterfaces = arrayOf(EnhancedTracker::class))

    private fun track(lastChapterRead: Double) = Track(
        id = 5L,
        mangaId = 1L,
        trackerId = 6L,
        remoteId = 9L,
        libraryId = null,
        title = "t",
        lastChapterRead = lastChapterRead,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    @Test
    fun `a server with no progress marks nothing read, a chapter numbered 0 included`() = runTest {
        sync().await(mangaId = 1L, remoteTrack = track(lastChapterRead = 0.0), tracker = server)

        marked shouldBe emptyList()
    }

    @Test
    fun `nothing read on either side pushes nothing to the server`() = runTest {
        sync().await(mangaId = 1L, remoteTrack = track(lastChapterRead = 0.0), tracker = server)

        coVerify(exactly = 0) { server.update(any(), any()) }
    }

    @Test
    fun `progress read locally is pushed to the server`() = runTest {
        sync(
            readLocally = setOf(0.0, 1.0),
        ).await(mangaId = 1L, remoteTrack = track(lastChapterRead = 0.0), tracker = server)

        coVerify { server.update(match { it.last_chapter_read == 1.0 }, any()) }
    }

    @Test
    fun `a server's progress marks every chapter up to it read, a chapter numbered 0 included`() = runTest {
        sync().await(mangaId = 1L, remoteTrack = track(lastChapterRead = 2.0), tracker = server)

        marked?.map { it.id } shouldBe listOf(0L, 10L, 20L)
    }
}
