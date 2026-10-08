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
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The rows a novel's reader lists in each scope. Novels 1 and 2 are one group; chapter 1's leading copy
 * is 10 (novel 1) and its other copy 20 (novel 2), chapter 2 is 21 (novel 2) alone.
 */
class NovelReadingRowsTest {

    private val chapterRepository = mockk<NovelChapterRepository>()
    private val novelRepository = mockk<NovelRepository>()
    private val mergeManager = mockk<NovelMergeManager>()
    private val mergedChapterProvider = mockk<NovelMergedChapterProvider>()
    private val interactor = GetNextNovelChapter(
        chapterRepository,
        novelRepository,
        NovelPreferences(InMemoryPreferenceStore(sequenceOf())),
        mergeManager,
        mergedChapterProvider,
        NovelChapterSettings(novelRepository),
    )

    @BeforeEach
    fun setUp() {
        coEvery { mergeManager.computeRelatedIds(any()) } returns longArrayOf(1L, 2L)
        coEvery { mergedChapterProvider.stitchOf(any()) } returns
            listOf(ChapterUnit(10, 0, 0), ChapterUnit(20, 0, 1), ChapterUnit(21, 1, 0))
        val render = NovelMergedChapterProvider(mockk(), mockk())
        every { mergedChapterProvider.merged(any(), any()) } answers { render.merged(firstArg(), secondArg()) }
        coEvery { novelRepository.getById(any()) } answers { Novel.create().copy(id = firstArg()) }
        coEvery { chapterRepository.getByNovelId(1L) } returns listOf(chapter(10, novelId = 1L, order = 1))
        coEvery { chapterRepository.getByNovelId(2L) } returns
            listOf(chapter(20, novelId = 2L, order = 1), chapter(21, novelId = 2L, order = 2))
    }

    private fun chapter(id: Long, novelId: Long, order: Long) = NovelChapter(
        id = id,
        novelId = novelId,
        url = "u$id",
        name = "Ch $order",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = order.toDouble(),
        sourceOrder = order,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private suspend fun rows(sourceScoped: Boolean, onDisk: Set<Long>) =
        interactor.readingRows(1L, sourceScoped, isInstalled = { true }) { chapters, _ ->
            chapters.mapNotNullTo(HashSet()) { chapter -> chapter.id.takeIf { it in onDisk } }
        }.rows.map { it.id }

    @Test
    fun `group scope lists another source's copy on disk in a merged row's place`() = runTest {
        rows(sourceScoped = false, onDisk = setOf(20L)) shouldBe listOf(20L, 21L)
    }

    @Test
    fun `group scope keeps the leading copy when no copy is on disk`() = runTest {
        rows(sourceScoped = false, onDisk = emptySet()) shouldBe listOf(10L, 21L)
    }

    @Test
    fun `source scope lists only the novel's own rows`() = runTest {
        rows(sourceScoped = true, onDisk = setOf(20L)) shouldBe listOf(10L)
    }

    @Test
    fun `a novel in no group lists its own rows`() = runTest {
        coEvery { mergeManager.computeRelatedIds(any()) } returns longArrayOf(1L)

        rows(sourceScoped = false, onDisk = emptySet()) shouldBe listOf(10L)
    }
}
