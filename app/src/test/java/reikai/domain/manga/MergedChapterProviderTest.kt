package reikai.domain.manga

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager

/**
 * Covers the reading-order policy [MergedChapterProvider.aggregate] adds on top of
 * [ChapterAggregation] (which has its own stitch/dedup tests): the unified list is ordered
 * newest-first and `sourceOrder` is reindexed so a "by source order" sort doesn't interleave sources.
 */
class MergedChapterProviderTest {

    private var nextId = 1L

    private fun chapter(mangaId: Long, number: Double): Chapter =
        Chapter.create().copy(id = nextId++, mangaId = mangaId, chapterNumber = number, name = "Chapter $number")

    private fun provider(preferred: List<Long> = emptyList()): MergedChapterProvider {
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { preferredMangaSources } returns mockk(relaxed = true) { every { get() } returns preferred }
        }
        // Non-gallery sources: a relaxed mock returns null from get(), so these serial-manga cases keep
        // the normal cross-source number dedup.
        val sourceManager = mockk<SourceManager>(relaxed = true)
        return MergedChapterProvider(mockk(), mockk(), sourceManager, preferences)
    }

    @Test
    fun `aggregate orders the unified list newest-first across sources`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0)) // 4 gap-fills from the other source

        val unified = provider().aggregate(mapOf(1L to source1, 2L to source2), mapOf(1L to 100L, 2L to 200L))

        unified.map { it.chapterNumber } shouldBe listOf(4.0, 3.0, 2.0, 1.0)
    }

    @Test
    fun `aggregate restamps source order to match reading order`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))

        val unified = provider().aggregate(mapOf(1L to source1, 2L to source2), mapOf(1L to 100L, 2L to 200L))

        unified.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }
}
