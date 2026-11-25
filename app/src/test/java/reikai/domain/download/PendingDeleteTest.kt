package reikai.domain.download

import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * The reader's delete-after-read waits in the pending list until the reader closes, and reading the list
 * clears it, so a chapter skipped then is never deleted. Manga only: the novel reader deletes through its
 * manager at once, which needs no source.
 */
class PendingDeleteTest {

    @Test
    fun `a pending chapter of an uninstalled extension is deleted`() = runTest {
        val manga = Manga.create().copy(id = 1L, source = 5L)
        val chapter = Chapter.create().copy(id = 10L, mangaId = manga.id)
        val deleted = mutableListOf<Long>()
        val manager = spyk(
            DownloadManager(
                context = mockk(relaxed = true),
                provider = mockk(),
                cache = mockk(),
                getCategories = mockk(),
                sourceManager = mockk<SourceManager> {
                    coEvery { get(manga.source) } returns null
                    coEvery { getOrStub(manga.source) } returns mockk()
                },
                downloadPreferences = mockk(),
                getManga = mockk(),
                getChapter = mockk(),
                downloader = mockk(),
                pendingDeleter = mockk { every { getPendingChapters() } returns mapOf(manga to listOf(chapter)) },
                sourceTitles = mockk(),
                getChaptersByMangaId = mockk(),
            ),
        ).also { spy ->
            coEvery { spy.deleteChapters(any(), any(), any()) } answers {
                deleted += firstArg<List<Chapter>>().map { it.id }
            }
        }

        manager.deletePendingChapters()

        deleted shouldBe listOf(10L)
    }
}
