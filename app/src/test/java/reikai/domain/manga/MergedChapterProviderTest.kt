package reikai.domain.manga

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

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

    @Test
    @DisplayName("an anchor that left the library loads on its own, so the reader can find it")
    fun anchorOutsideLibraryLoadsStandalone() = runTest {
        val preferences = mockk<ReikaiLibraryPreferences>(relaxed = true) {
            every { seriesMergingEnabled } returns mockk(relaxed = true) { every { get() } returns true }
        }
        val repository = mockk<MergeGroupRepository>(relaxed = true) {
            coEvery { getGroupId(ContentType.MANGA, 1L) } returns 7L
            coEvery { getFavoriteMembers(ContentType.MANGA, 7L) } returns listOf(2L, 3L)
        }
        val provider = MergedChapterProvider(
            getMangaWithChapters = mockk(relaxed = true),
            mergeManager = MangaMergeManager(repository, preferences) {},
            sourceManager = mockk(relaxed = true),
            reikaiLibraryPreferences = preferences,
            units = mockk(relaxed = true),
            reconcile = mockk(relaxed = true),
        )

        provider.load(Manga.create().copy(id = 1L)).mangaById.keys shouldBe setOf(1L)
    }
}
