package reikai.domain.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

class DownloadedChaptersTest {

    private val first = Manga.create().copy(id = 1L, source = 10L, title = "first")
    private val second = Manga.create().copy(id = 2L, source = 20L, title = "second")
    private val local = Manga.create().copy(id = 3L, source = LocalSource.ID, title = "local")

    // Each folder holds only the chapters stored under that owner, as the download cache answers.
    private val onDisk = mapOf(first to setOf(11L), second to setOf(21L))
    private val downloadManager = mockk<DownloadManager> {
        every { getDownloadedChapterIds(any(), any()) } answers {
            val owned = onDisk[secondArg<Manga>()].orEmpty()
            firstArg<List<Chapter>>().map { it.id }.filterTo(HashSet()) { it in owned }
        }
    }

    @Test
    fun `each chapter is looked up in its own owner's folder`() {
        val chapters = listOf(chapter(11L, 1L), chapter(12L, 1L), chapter(21L, 2L), chapter(22L, 2L))
        val owners = mapOf(1L to first, 2L to second)

        downloadManager.downloadedChapterIds(chapters) { owners.getValue(it.mangaId) } shouldBe setOf(11L, 21L)
    }

    @Test
    fun `a local entry's chapters are all on disk`() {
        val chapters = listOf(chapter(31L, 3L), chapter(32L, 3L))

        downloadManager.downloadedChapterIds(chapters) { local } shouldBe setOf(31L, 32L)
    }

    private fun chapter(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId, name = "$id")
}
