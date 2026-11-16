package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelChapterSyncRules
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelChapterSaver.SaveResult
import reikai.novel.host.SourceNovel
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Instant

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
            syncRules = NovelChapterSyncRules(mockk(), InMemoryChapterCopyMarks(), NoChapterTombstones),
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

    private val chapter = NovelChapter(
        id = 7L, novelId = 1L, url = "/1/7", name = "Chapter 7", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 7.0, sourceOrder = 0L,
        dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
    )

    private fun textFetcher(text: suspend () -> String, saved: SaveResult = SaveResult.SAVED): NovelPageFetcher {
        val fetch = mockk<NovelPageFetch> { coEvery { chapterText(any(), any(), any()) } coAnswers { text() } }
        val source = mockk<NovelSource> { every { pageFetch } returns fetch }
        return NovelPageFetcher(
            novelRepo = mockk<NovelRepository> { coEvery { getById(1L) } returns novel },
            chapterRepo = mockk<NovelChapterRepository> {
                coEvery { getById(7L) } returns chapter
                coEvery { getByNovelId(1L) } returns emptyList()
            },
            sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns source },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            syncRules = NovelChapterSyncRules(mockk(), InMemoryChapterCopyMarks(), NoChapterTombstones),
            coverCache = mockk(relaxed = true),
            saver = mockk { coEvery { save(any(), any(), any(), any()) } returns saved },
            novelDownloadManager = { mockk(relaxed = true) },
        )
    }

    /** The reader reloads an open chapter, or retries a failed one, on this. */
    @Test
    fun `a saved chapter's text is announced to the reader`() = runTest {
        val fetcher = textFetcher({ "<p>Text</p>" })
        val announced = async(start = CoroutineStart.UNDISPATCHED) { fetcher.chapterSaved.first() }

        fetcher.useForChapterText(7L, "https://src.example/1/7", "<html/>")

        announced.await() shouldBe 7L
    }

    @Test
    fun `a page with no chapter text says so`() = runTest {
        textFetcher({ " " }).useForChapterText(7L, "https://src.example/1/7", "<html/>") shouldBe
            NovelPageFetcher.ChapterFromPage.NO_TEXT
    }

    @Test
    fun `a chapter whose file name another chapter holds says so`() = runTest {
        textFetcher({ "<p>Text</p>" }, SaveResult.NAME_TAKEN).useForChapterText(7L, "u", "<html/>") shouldBe
            NovelPageFetcher.ChapterFromPage.NAME_TAKEN
    }

    @Test
    fun `a source parser that throws on the page is reported, not thrown`() = runTest {
        textFetcher({ throw IllegalStateException("parser") }).useForChapterText(7L, "u", "<html/>") shouldBe
            NovelPageFetcher.ChapterFromPage.UNREADABLE
    }
}
