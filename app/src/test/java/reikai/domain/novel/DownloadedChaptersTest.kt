package reikai.domain.novel

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import reikai.novel.local.LocalNovelSource
import kotlin.time.Instant

/** A merged list pools several novels' chapters, and each is on disk under its own novel's folder. */
class DownloadedChaptersTest {

    private val first = Novel.create().copy(id = 1L, source = "a", title = "One")
    private val second = Novel.create().copy(id = 2L, source = "b", title = "Two")

    // Each novel's folder holds files by chapter name, as the real index matches them, so a chapter probed
    // under another novel is found when that folder holds a file of its name.
    private val folders = mapOf(first to setOf("Chapter 5"), second to setOf("Chapter 7"))
    private val cache = mockk<NovelDownloadCache> {
        every { downloadedChapterIds(any<Novel>(), any()) } answers {
            val names = folders[firstArg<Novel>()].orEmpty()
            secondArg<List<NovelChapter>>().filter { it.name in names }.mapTo(HashSet()) { it.id }
        }
    }

    @Test
    fun `chapters of two novels are each found under their own novel`() {
        // 21 shares its name with the first novel's download, but is not downloaded under its own.
        val chapters = listOf(
            chapter(10L, novelId = 1L, name = "Chapter 5"),
            chapter(20L, novelId = 2L, name = "Chapter 7"),
            chapter(21L, novelId = 2L, name = "Chapter 5"),
        )

        cache.downloadedChapterIds(chapters, mapOf(1L to first, 2L to second)) shouldBe setOf(10L, 20L)
    }

    @Test
    fun `a chapter whose novel is no longer stored is not on disk`() {
        val chapters =
            listOf(chapter(10L, novelId = 1L, name = "Chapter 5"), chapter(20L, novelId = 2L, name = "Chapter 5"))

        cache.downloadedChapterIds(chapters, mapOf(1L to first)) shouldBe setOf(10L)
    }

    @Test
    fun `a local novel's chapter is on the device with nothing downloaded`() {
        every { cache.isChapterDownloaded(any<Novel>(), any()) } returns false
        val local = Novel.create().copy(id = 3L, source = LocalNovelSource.ID, title = "Local")

        cache.isOnDevice(local, chapter(30L, novelId = 3L, name = "Chapter 1")) shouldBe true
    }

    private fun chapter(id: Long, novelId: Long, name: String) = NovelChapter(
        id = id,
        novelId = novelId,
        url = "/c/$id",
        name = name,
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null,
        page = "",
    )
}
