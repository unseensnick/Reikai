package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter

class GetNextNovelChapterTest {

    private val chapterRepository = mockk<NovelChapterRepository>()
    private val novelRepository = mockk<NovelRepository>(relaxed = true)
    private val mergeManager = mockk<NovelMergeManager>(relaxed = true)
    private val libraryPreferences = mockk<ReikaiLibraryPreferences>(relaxed = true)
    private val interactor =
        GetNextNovelChapter(chapterRepository, novelRepository, mergeManager, libraryPreferences)

    @BeforeEach
    fun setUp() {
        // Unmerged unless a test says otherwise, which is what the repository returns for a lone entry.
        coEvery { mergeManager.computeRelatedIds(any()) } answers { longArrayOf(firstArg()) }
        every { libraryPreferences.preferredNovelSources } returns
            mockk(relaxed = true) { every { get() } returns emptyList() }
    }

    private fun chapter(id: Long, order: Long, read: Boolean, novelId: Long = 1L) = NovelChapter(
        id = id,
        novelId = novelId,
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

    // The group half: what a collapsed recents row and the library's continue button both resolve
    // through. Manga twin: LibraryViewModel.getNextUnreadChapter over MergedChapterProvider.

    private fun merged() {
        coEvery { mergeManager.computeRelatedIds(any()) } returns longArrayOf(1L, 2L)
        coEvery { mergeManager.overrideRankingMemberIds(any()) } returns emptyList()
        coEvery { novelRepository.getById(any()) } returns null
    }

    @Test
    fun `the first unread of a merged novel pools every source`() = runTest {
        merged()
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(chapter(10, 1, read = true))
        coEvery { chapterRepository.getByNovelId(2L) } returns listOf(
            chapter(20, 1, read = true, novelId = 2L),
            chapter(21, 2, read = false, novelId = 2L),
        )

        interactor.awaitFirstUnreadInGroup(novelId = 1L)?.id shouldBe 21L
    }

    @Test
    fun `a chapter read on another source is not offered as the next unread`() = runTest {
        merged()
        // Same chapter on both sources, read on the second: the stitch keeps the trunk's unread copy.
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 1, read = false),
            chapter(11, 2, read = false),
        )
        coEvery { chapterRepository.getByNovelId(2L) } returns listOf(chapter(20, 1, read = true, novelId = 2L))

        interactor.awaitFirstUnreadInGroup(novelId = 1L)?.id shouldBe 11L
    }

    @Test
    fun `an unmerged novel resolves its own first unread`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = true),
            chapter(11, 1, read = false),
        )

        interactor.awaitFirstUnreadInGroup(novelId = 1L)?.id shouldBe 11L
    }
}
