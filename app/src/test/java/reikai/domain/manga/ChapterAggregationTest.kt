package reikai.domain.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(mangaId: Long, number: Double, scanlator: String? = null): Chapter =
        Chapter.create().copy(
            id = nextId++,
            mangaId = mangaId,
            chapterNumber = number,
            scanlator = scanlator,
            name = "Chapter $number",
        ).let { it.copy(url = "/$mangaId/$number/${scanlator.orEmpty()}/${it.id}") }

    private fun List<Chapter>.numbers(): List<Double> = map { it.chapterNumber }.sorted()

    @Test
    fun `trunk is the source with the most distinct numbers, not the most rows`() {
        // Source 1: 3 real chapters, each duplicated across two scanlators -> 6 rows, 3 distinct.
        val source1 = listOf(
            chapter(1L, 1.0, "A"), chapter(1L, 1.0, "B"),
            chapter(1L, 2.0, "A"), chapter(1L, 2.0, "B"),
            chapter(1L, 3.0, "A"), chapter(1L, 3.0, "B"),
        )
        // Source 2: 5 real chapters, single scanlator -> 5 rows, 5 distinct.
        val source2 = listOf(
            chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0), chapter(2L, 4.0), chapter(2L, 5.0),
        )

        val unified = ChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // Source 2 wins the trunk despite source 1 having more rows; source 1 adds nothing new.
        unified.size shouldBe 5
        unified.map { it.mangaId }.distinct() shouldBe listOf(2L)
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val other = listOf(chapter(2L, 4.0), chapter(2L, 5.0), chapter(2L, 6.0), chapter(2L, 7.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        // Trunk owns 1..5; only the missing 6 and 7 are borrowed from source 2.
        unified.first { it.chapterNumber == 5.0 }.mangaId shouldBe 1L
        unified.first { it.chapterNumber == 6.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 7.0 }.mangaId shouldBe 2L
    }

    @Test
    fun `collapses scanlator duplicates when gap-filling`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        // Number 3 appears twice (two scanlators) in the gap-filled source.
        val other = listOf(chapter(2L, 3.0, "A"), chapter(2L, 3.0, "B"), chapter(2L, 4.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.count { it.chapterNumber == 3.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `collapses the trunk's own scanlator variants to one row per number`() {
        val trunk = listOf(chapter(1L, 1.0, "A"), chapter(1L, 1.0, "B"), chapter(1L, 2.0))
        val other = listOf(chapter(2L, 1.0), chapter(2L, 2.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.count { it.chapterNumber == 1.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0)
        unified.map { it.mangaId }.distinct() shouldBe listOf(1L)
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val other = listOf(chapter(2L, -1.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        // The unrecognized (-1) sibling chapter can't be matched by number, so it's dropped; 3 fills.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0)
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1.0, "A"), chapter(1L, 1.0, "B"), chapter(1L, -1.0))

        val unified = ChapterAggregation.aggregate(mapOf(1L to only))

        unified shouldBe only
    }

    @Test
    fun `empty input returns empty`() {
        ChapterAggregation.aggregate(emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer distinct numbers`() {
        // Source 1 has more distinct numbers, but source 2's source id is preferred.
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(200L),
        )

        // Source 2 wins the trunk (1..3); source 1 only gap-fills 4 and 5.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.mangaId shouldBe 1L
    }

    @Test
    fun `multiple preferred sources order by list index, not distinct count`() {
        val sourceA = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val sourceB = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val sourceC = listOf(chapter(3L, 1.0), chapter(3L, 2.0), chapter(3L, 3.0), chapter(3L, 4.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L, 3L to 300L),
            // B is ranked first, then A; C is unranked (most distinct, but lowest priority).
            preferredSourceIds = listOf(200L, 100L),
        )

        // B (rank 0) is the trunk owning 1..3; C only gap-fills 4 despite having the most chapters.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
        unified.first { it.chapterNumber == 1.0 }.mangaId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.mangaId shouldBe 3L
    }

    @Test
    fun `a preferred id not present in the group falls back to distinct count`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(999L), // no source in the group has this id
        )

        // Unchanged from the no-preference case: source 1 (most distinct) is the trunk.
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.map { it.mangaId }.distinct() shouldBe listOf(1L)
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))
        val bySource = mapOf(1L to source1, 2L to source2)

        val withDefaults = ChapterAggregation.aggregate(bySource)
        val withEmptyPrefs = ChapterAggregation.aggregate(
            chaptersBySource = bySource,
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = emptyList(),
        )

        withEmptyPrefs shouldBe withDefaults
    }
}
