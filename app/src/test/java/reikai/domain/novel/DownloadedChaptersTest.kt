package reikai.domain.novel

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache

/** A merged list pools several novels' chapters, and each is on disk under its own novel's folder. */
class DownloadedChaptersTest {

    private val first = Novel.create().copy(id = 1L, source = "a", title = "One")
    private val second = Novel.create().copy(id = 2L, source = "b", title = "Two")

    // The index answers a chapter as on disk only when it is asked under its own novel.
    private val cache = mockk<NovelDownloadCache> {
        every { downloadedChapterIds(any<Novel>(), any()) } answers {
            val novel = firstArg<Novel>()
            secondArg<List<NovelChapter>>().filter { it.novelId == novel.id }.mapTo(HashSet()) { it.id }
        }
    }

    @Test
    fun `chapters of two novels are each found under their own novel`() {
        val chapters = listOf(chapter(10L, novelId = 1L), chapter(20L, novelId = 2L))

        cache.downloadedChapterIds(chapters, mapOf(1L to first, 2L to second)) shouldBe setOf(10L, 20L)
    }

    @Test
    fun `a chapter whose novel is no longer stored is not on disk`() {
        val chapters = listOf(chapter(10L, novelId = 1L), chapter(20L, novelId = 2L))

        cache.downloadedChapterIds(chapters, mapOf(1L to first)) shouldBe setOf(10L)
    }

    private fun chapter(id: Long, novelId: Long) = NovelChapter(
        id = id,
        novelId = novelId,
        url = "/c/$id",
        name = "Chapter $id",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
