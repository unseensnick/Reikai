package reikai.domain.novel.track

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack

class PushNovelUnreadTest {

    private fun chapter(id: Long, novelId: Long) = NovelChapter(
        id = id, novelId = novelId, url = "", name = "", read = true, bookmark = false,
        lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    private val track = NovelTrack(
        id = 5L, novelId = 1L, trackerId = 100L, remoteId = 9L, libraryId = null, title = "t",
        lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0, remoteUrl = "",
        startDate = 0, finishDate = 0, private = false,
    )

    @Test
    fun `an unread across two merged sources reaches their shared tracker once`() = runTest {
        val push = PushNovelUnread(
            getNovelTracks = mockk<GetNovelTracks> { coEvery { awaitGroup(any()) } returns listOf(track) },
            trackerManager = mockk(),
            insertNovelTrack = mockk(),
        )

        push.tracksFor(listOf(chapter(8, novelId = 1L), chapter(10, novelId = 2L))) shouldBe listOf(track)
    }
}
