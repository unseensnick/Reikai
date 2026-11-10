package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.host.SourceNovel
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

class NovelPageFetcherTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", url = "/1", title = "N", totalPages = 2L)
    private val written = mutableListOf<NovelUpdate>()

    private suspend fun useDetailsPage(storedChapters: List<NovelChapter>) {
        val fetch = mockk<NovelPageFetch> {
            coEvery { details(any(), any(), any()) } returns SourceNovel(path = "/1", name = "N", totalPages = 4)
        }
        val source = mockk<NovelSource> {
            every { id } returns "src"
            every { pageFetch } returns fetch
        }
        NovelPageFetcher(
            novelRepo = mockk<NovelRepository> {
                coEvery { getById(1L) } returns novel
                coEvery { update(any<NovelUpdate>()) } answers {
                    written += firstArg<NovelUpdate>()
                    true
                }
            },
            chapterRepo = mockk<NovelChapterRepository> { coEvery { getByNovelId(1L) } returns storedChapters },
            sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns source },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            chapterNumberOverrides = mockk(),
            chapterCopyMarks = InMemoryChapterCopyMarks(),
            localTombstones = NoChapterTombstones,
            coverCache = mockk(relaxed = true),
            saver = mockk(),
            novelDownloadManager = { mockk(relaxed = true) },
        ).useForDetails(1L, "https://src.example/1", "<html/>")
    }

    /** The update walks on from the stored count, so raising it here would skip the pages between. */
    @Test
    fun `a details page leaves the page count of a novel with chapters`() = runTest {
        useDetailsPage(listOf(mockk()))

        written.single().totalPages shouldBe 2L
    }

    /** Nothing is stored to walk from, and the details screen builds its page selector from the count. */
    @Test
    fun `a details page gives a novel with no chapters its page count`() = runTest {
        useDetailsPage(emptyList())

        written.single().totalPages shouldBe 4L
    }
}
