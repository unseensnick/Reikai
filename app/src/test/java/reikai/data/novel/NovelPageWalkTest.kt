package reikai.data.novel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.novel.model.Novel
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource

class NovelPageWalkTest {

    @Test
    fun `a cancelled page stops the walk instead of moving on to the next page`() = runTest {
        val source = CancellingSource(cancelOnPage = "2")

        shouldThrow<CancellationException> {
            walkNovelPages(
                novel = Novel.create().copy(id = 1L, url = "/novel", totalPages = 5L),
                source = source,
                walkedThrough = 1L,
                toPage = 5L,
                novelChapterRepository = mockk(),
                novelRepository = mockk(),
                libraryPreferences = mockk(),
                syncRules = NovelChapterSyncRules(
                    NoChapterNumberOverrides,
                    InMemoryChapterCopyMarks(),
                    NoChapterTombstones,
                ),
                novelDownloadManager = mockk(relaxed = true),
            )
        }

        source.pagesEntered shouldBe listOf("2")
    }

    @Test
    fun `a walk cancelled while it writes a page's chapters stops there`() = runTest {
        val source = CancellingSource(cancelOnPage = null)

        shouldThrow<CancellationException> {
            walkNovelPages(
                novel = Novel.create().copy(id = 1L, url = "/novel", totalPages = 5L),
                source = source,
                walkedThrough = 1L,
                toPage = 5L,
                novelChapterRepository = mockk {
                    coEvery { getByNovelId(any()) } throws CancellationException("stopped")
                },
                novelRepository = mockk(),
                libraryPreferences = mockk(),
                syncRules = NovelChapterSyncRules(
                    NoChapterNumberOverrides,
                    InMemoryChapterCopyMarks(),
                    NoChapterTombstones,
                ),
                novelDownloadManager = mockk(relaxed = true),
            )
        }

        source.pagesEntered shouldBe listOf("2")
    }

    // A challenge page answers without an error and lists nothing; counting it walked skipped it for good.
    @Test
    fun `a page that lists nothing on a growth walk is walked again next time`() = runTest {
        val walk = walkNovelPages(
            novel = Novel.create().copy(id = 1L, url = "/novel", totalPages = 2L),
            source = CancellingSource(cancelOnPage = null, emptyPages = setOf("2")),
            walkedThrough = 1L,
            toPage = 2L,
            novelChapterRepository = mockk(),
            novelRepository = mockk(),
            libraryPreferences = mockk(),
            syncRules = NovelChapterSyncRules(
                NoChapterNumberOverrides,
                InMemoryChapterCopyMarks(),
                NoChapterTombstones,
            ),
            novelDownloadManager = mockk(relaxed = true),
        )

        walk.walkedThrough shouldBe 1L
    }

    /** Cancels on [cancelOnPage], lists nothing on [emptyPages], as a challenge page does; any other lists one chapter. */
    private class CancellingSource(
        private val cancelOnPage: String?,
        private val emptyPages: Set<String> = emptySet(),
    ) : NovelSource {
        val pagesEntered = mutableListOf<String>()

        override val id = "src"
        override val name = "Source"
        override val version = "1.0.0"
        override val site = "https://src.example"
        override val lang = "en"
        override val iconUrl: String? = null
        override val format = NovelExtensionFormat.JS
        override val extensionName = "Source"
        override val contentWarning = ContentWarning.SAFE

        override suspend fun parsePage(novelPath: String, page: String): SourceNovel? {
            pagesEntered += page
            if (page == cancelOnPage) throw CancellationException("refresh cancelled")
            if (page in emptyPages) return SourceNovel(path = novelPath, chapters = emptyList())
            return SourceNovel(
                path = novelPath,
                chapters = listOf(ChapterItem(name = "Chapter $page", path = "/c/$page")),
            )
        }

        override suspend fun parseNovel(novelPath: String): SourceNovel = unused()

        override suspend fun parseChapter(chapterPath: String): String = unused()

        override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
            unused()

        override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("Not part of a page walk")
    }
}
