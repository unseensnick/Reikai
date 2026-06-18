package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter

class GetNextNovelChapterTest {

    private val chapterRepository = mockk<NovelChapterRepository>()
    private val interactor = GetNextNovelChapter(chapterRepository)

    private fun chapter(id: Long, order: Long, read: Boolean) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = "Ch $order",
        read = read,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = order.toDouble(),
        sourceOrder = order,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
        isDownloaded = false,
    )

    @Test
    fun `reopens the recorded chapter when it is not fully read`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = true),
            chapter(11, 1, read = false),
            chapter(12, 2, read = false),
        )
        interactor.await(novelId = 1L, fromChapterId = 11L)?.id shouldBe 11L
    }

    @Test
    fun `advances to the next chapter when the recorded one is read`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = true),
            chapter(11, 1, read = false),
        )
        interactor.await(novelId = 1L, fromChapterId = 10L)?.id shouldBe 11L
    }

    @Test
    fun `returns null when the recorded chapter is the last and read`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(chapter(10, 0, read = true))
        interactor.await(novelId = 1L, fromChapterId = 10L) shouldBe null
    }

    @Test
    fun `returns null when the recorded chapter is missing`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(chapter(10, 0, read = false))
        interactor.await(novelId = 1L, fromChapterId = 999L) shouldBe null
    }
}
