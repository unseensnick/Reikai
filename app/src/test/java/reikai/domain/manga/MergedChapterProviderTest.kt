package reikai.domain.manga

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import tachiyomi.domain.chapter.model.Chapter

/**
 * The reading-order policy the provider adds on top of the stored stitch: `sourceOrder` is reindexed
 * so a "by source order" sort reads top to bottom instead of interleaving the sources. Where a reader
 * puts a chapter the stitch dropped is [reikai.domain.merge.OpenedChapterTest]'s. What the stitch itself
 * decides is [reikai.domain.merge.StoredStitchTest]'s; how it is built is [ChapterAggregationTest]'s.
 */
class MergedChapterProviderTest {

    private var nextId = 1L

    private fun chapter(mangaId: Long, number: Double): Chapter =
        Chapter.create().copy(id = nextId++, mangaId = mangaId, chapterNumber = number, name = "Chapter $number")

    private fun provider() = MergedChapterProvider(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    @Test
    @DisplayName("the merged list is renumbered onto one source-order scale")
    fun mergedRestampsSourceOrder() = runTest {
        val chapters = listOf(chapter(1L, 3.0), chapter(1L, 2.0), chapter(2L, 1.0))
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index * 10L) }
        val stitch = chapters.mapIndexed { index, chapter -> ChapterUnit(chapter.id, index, 0) }

        val merged = provider().merged(chapters, stitch)

        merged.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L)
    }

    @Test
    @DisplayName("an ungrouped entry keeps its own source order")
    fun ungroupedKeepsItsOwnOrder() = runTest {
        val chapters = listOf(chapter(1L, 1.0).copy(sourceOrder = 7L), chapter(1L, 2.0).copy(sourceOrder = 9L))

        provider().merged(chapters, emptyList()) shouldBe chapters
    }
}
