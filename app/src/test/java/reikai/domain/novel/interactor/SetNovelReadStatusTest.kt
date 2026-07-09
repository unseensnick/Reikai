package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter

class SetNovelReadStatusTest {

    private fun chapter(id: Long, novelId: Long = 1L, read: Boolean = false, progress: Long = 0L) = NovelChapter(
        id = id, novelId = novelId, url = "", name = "", read = read, bookmark = false,
        lastTextProgress = progress, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "", isDownloaded = false,
    )

    private fun interactor(
        chapterRepository: NovelChapterRepository = mockk(relaxed = true),
        deleteAfterRead: DeleteNovelChaptersAfterRead = mockk(relaxed = true),
    ) = SetNovelReadStatus(chapterRepository, deleteAfterRead)

    @Test
    fun `marking read updates only the chapters that are not already read`() = runTest {
        val chapterRepo = mockk<NovelChapterRepository>(relaxed = true)

        interactor(chapterRepository = chapterRepo)
            .await(read = true, chapters = listOf(chapter(1, read = false), chapter(2, read = true)))

        coVerify { chapterRepo.setReadBulk(listOf(1L), true) }
    }

    @Test
    fun `marking read when every chapter is already read is a no-op`() = runTest {
        val result = interactor()
            .await(read = true, chapters = listOf(chapter(1, read = true), chapter(2, read = true)))

        result shouldBe SetNovelReadStatus.Result.NoChapters
    }

    @Test
    fun `marking read deletes finished downloads grouped per novel`() = runTest {
        val deleteAfterRead = mockk<DeleteNovelChaptersAfterRead>(relaxed = true)
        val chA = chapter(1, novelId = 10L)
        val chB = chapter(2, novelId = 20L)

        interactor(deleteAfterRead = deleteAfterRead).await(read = true, chapters = listOf(chA, chB))

        coVerify { deleteAfterRead.await(10L, listOf(chA)) }
        coVerify { deleteAfterRead.await(20L, listOf(chB)) }
    }

    @Test
    fun `marking unread never deletes downloads`() = runTest {
        val deleteAfterRead = mockk<DeleteNovelChaptersAfterRead>(relaxed = true)

        interactor(deleteAfterRead = deleteAfterRead)
            .await(read = false, chapters = listOf(chapter(1, read = true)))

        coVerify(exactly = 0) { deleteAfterRead.await(any(), any()) }
    }
}
