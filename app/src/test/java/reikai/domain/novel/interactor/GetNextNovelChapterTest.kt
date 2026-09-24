package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class GetNextNovelChapterTest {

    private val chapterRepository = mockk<NovelChapterRepository>()
    private val novelRepository = mockk<NovelRepository>()
    private val mergeManager = mockk<NovelMergeManager>(relaxed = true)
    private val mergedChapterProvider = mockk<NovelMergedChapterProvider>()
    private val interactor = GetNextNovelChapter(
        chapterRepository,
        novelRepository,
        NovelPreferences(InMemoryPreferenceStore(sequenceOf())),
        mergeManager,
        mergedChapterProvider,
    )

    @BeforeEach
    fun setUp() {
        // Unmerged unless a test says otherwise, which is what the repository returns for a lone entry.
        coEvery { mergeManager.computeRelatedIds(any()) } answers { longArrayOf(firstArg()) }
        // Sorted by source order unless a test sets the novel's own sort.
        coEvery { novelRepository.getById(any()) } returns Novel.create()
    }

    private fun chapter(
        id: Long,
        order: Long,
        read: Boolean,
        novelId: Long = 1L,
        name: String = "Ch $order",
        bookmark: Boolean = false,
    ) =
        NovelChapter(
            id = id,
            novelId = novelId,
            url = "u$id",
            name = name,
            read = read,
            bookmark = bookmark,
            lastTextProgress = 0L,
            chapterNumber = order.toDouble(),
            sourceOrder = order,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )

    /** The library's continue button on novel 1, with [downloaded] standing in for the download cache. */
    private suspend fun resume(downloadedOnly: Boolean = false, downloaded: Set<Long> = emptySet()) =
        interactor.awaitFirstUnreadInGroup(1L, downloadedOnly) { _, chapters ->
            chapters.mapNotNullTo(HashSet()) { chapter -> chapter.id.takeIf { it in downloaded } }
        }?.id

    // The group half: what a collapsed recents row and the library's continue button both resolve
    // through. Manga twin: LibraryViewModel.getNextUnreadChapter over MergedChapterProvider.

    /** A two-source group whose stored stitch is [stitch]; the real render and source-order restamp run
     *  over it, so what the interactor sees is what a screen would see. */
    private fun merged(stitch: List<ChapterUnit>) {
        coEvery { mergeManager.computeRelatedIds(any()) } returns longArrayOf(1L, 2L)
        coEvery { mergedChapterProvider.stitchOf(any()) } returns stitch
        val render = NovelMergedChapterProvider(mockk(), mockk(), mockk())
        every { mergedChapterProvider.merged(any(), any()) } answers { render.merged(firstArg(), secondArg()) }
    }

    @Test
    fun `the first unread of a merged novel pools every source`() = runTest {
        merged(listOf(ChapterUnit(10, 0, 0), ChapterUnit(20, 0, 1), ChapterUnit(21, 1, 0)))
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(chapter(10, 1, read = true))
        coEvery { chapterRepository.getByNovelId(2L) } returns listOf(
            chapter(20, 1, read = true, novelId = 2L),
            chapter(21, 2, read = false, novelId = 2L),
        )

        resume() shouldBe 21L
    }

    @Test
    fun `a chapter read on another source is not offered as the next unread`() = runTest {
        // Same chapter on both sources, read on the second: the stitch keeps the trunk's unread copy.
        merged(listOf(ChapterUnit(10, 0, 0), ChapterUnit(20, 0, 1), ChapterUnit(11, 1, 0)))
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 1, read = false),
            chapter(11, 2, read = false),
        )
        coEvery { chapterRepository.getByNovelId(2L) } returns listOf(chapter(20, 1, read = true, novelId = 2L))

        resume() shouldBe 11L
    }

    @Test
    fun `the first unread of a merged novel follows the novel's own chapter sort`() = runTest {
        // The stitch runs Gamma, Alpha, Beta; alphabetically, with Alpha read on the other source, the
        // next is Beta (11), where the stitch alone would say Gamma (10).
        merged(listOf(ChapterUnit(10, 0, 0), ChapterUnit(20, 1, 0), ChapterUnit(11, 2, 0)))
        coEvery { novelRepository.getById(1L) } returns Novel.create()
            .copy(chapterFlags = NovelChapterFlags.SORT_LOCAL or NovelChapterFlags.SORTING_ALPHABET)
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 1, read = false, name = "Gamma"),
            chapter(11, 2, read = false, name = "Beta"),
        )
        coEvery { chapterRepository.getByNovelId(2L) } returns listOf(
            chapter(20, 1, read = true, novelId = 2L, name = "Alpha"),
        )

        resume() shouldBe 11L
    }

    @Test
    fun `an unmerged novel resolves its own first unread`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = true),
            chapter(11, 1, read = false),
        )

        resume() shouldBe 11L
    }

    @Test
    fun `the first unread follows the novel's own chapter sort`() = runTest {
        // Alphabetically the order is Alpha, Beta, Gamma, so with Alpha read the answer is Beta (11),
        // not the source's next listing (12). This is the order the reader pages in.
        coEvery { novelRepository.getById(1L) } returns Novel.create()
            .copy(chapterFlags = NovelChapterFlags.SORT_LOCAL or NovelChapterFlags.SORTING_ALPHABET)
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = true, name = "Alpha"),
            chapter(12, 1, read = false, name = "Gamma"),
            chapter(11, 2, read = false, name = "Beta"),
        )

        resume() shouldBe 11L
    }

    // The novel's own chapter filters narrow what the button may open, as the manga library's
    // getNextUnread and the novel details button already do.

    @Test
    fun `a novel filtered to bookmarked chapters resumes at the first bookmarked unread`() = runTest {
        coEvery { novelRepository.getById(1L) } returns Novel.create()
            .copy(chapterFlags = NovelChapterFlags.FILTER_LOCAL or NovelChapterFlags.SHOW_BOOKMARKED)
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = false),
            chapter(11, 1, read = false, bookmark = true),
        )

        resume() shouldBe 11L
    }

    @Test
    fun `downloaded only resumes at the first downloaded unread`() = runTest {
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(
            chapter(10, 0, read = false),
            chapter(11, 1, read = false),
        )

        resume(downloadedOnly = true, downloaded = setOf(11L)) shouldBe 11L
    }
}
