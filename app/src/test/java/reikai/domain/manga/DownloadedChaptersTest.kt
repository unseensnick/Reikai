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

    // Each owner's folder holds chapter folders by name, as the download cache matches them, so a chapter
    // probed under another owner is found when that folder holds one of its name.
    private val folders = mapOf(first to setOf("Ch 1"), second to setOf("Ch 2"))
    private val downloadManager = mockk<DownloadManager> {
        every { getDownloadedChapterIds(any(), any()) } answers {
            val names = folders[secondArg<Manga>()].orEmpty()
            firstArg<List<Chapter>>().filter { it.name in names }.mapTo(HashSet()) { it.id }
        }
    }

    @Test
    fun `each chapter is looked up in its own owner's folder`() {
        // 12 and 22 share a name with the other owner's download, but neither is downloaded under its own.
        val chapters =
            listOf(
                chapter(11L, 1L, "Ch 1"),
                chapter(12L, 1L, "Ch 2"),
                chapter(21L, 2L, "Ch 2"),
                chapter(22L, 2L, "Ch 1"),
            )
        val owners = mapOf(1L to first, 2L to second)

        downloadManager.downloadedChapterIds(chapters) { owners.getValue(it.mangaId) } shouldBe setOf(11L, 21L)
    }

    @Test
    fun `a local entry's chapters are all on disk`() {
        val chapters = listOf(chapter(31L, 3L, "Ch 31"), chapter(32L, 3L, "Ch 32"))

        downloadManager.downloadedChapterIds(chapters) { local } shouldBe setOf(31L, 32L)
    }

    @Test
    fun `one copy of a local entry's chapter is on the device`() {
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns false

        downloadManager.isOnDevice("Ch 31", null, "/31", local.title, local.source) shouldBe true
    }

    private fun chapter(id: Long, mangaId: Long, name: String) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        name = name,
    )
}
