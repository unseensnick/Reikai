package yokai.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(mangaId: Long, number: Float, scanlator: String? = null): Chapter =
        Chapter.create().apply {
            id = nextId++
            manga_id = mangaId
            chapter_number = number
            this.scanlator = scanlator
            url = "/$mangaId/$number/${scanlator.orEmpty()}/$id"
            name = "Chapter $number"
        }

    private fun List<Chapter>.numbers(): List<Float> = map { it.chapter_number }.sorted()

    @Test
    fun `trunk is the source with the most distinct numbers, not the most rows`() {
        // Source 1: 3 real chapters, each duplicated across two scanlators -> 6 rows, 3 distinct.
        val source1 = listOf(
            chapter(1L, 1f, "A"), chapter(1L, 1f, "B"),
            chapter(1L, 2f, "A"), chapter(1L, 2f, "B"),
            chapter(1L, 3f, "A"), chapter(1L, 3f, "B"),
        )
        // Source 2: 5 real chapters, single scanlator -> 5 rows, 5 distinct.
        val source2 = listOf(
            chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f), chapter(2L, 4f), chapter(2L, 5f),
        )

        val unified = ChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // Source 2 wins the trunk despite source 1 having more rows; source 1 adds nothing new.
        assertEquals(5, unified.size)
        assertEquals(listOf(2L), unified.map { it.manga_id }.distinct())
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), unified.numbers())
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f), chapter(1L, 4f), chapter(1L, 5f))
        val other = listOf(chapter(2L, 4f), chapter(2L, 5f), chapter(2L, 6f), chapter(2L, 7f))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), unified.numbers())
        // Trunk owns 1..5; only the missing 6 and 7 are borrowed from source 2.
        assertEquals(1L, unified.first { it.chapter_number == 5f }.manga_id)
        assertEquals(2L, unified.first { it.chapter_number == 6f }.manga_id)
        assertEquals(2L, unified.first { it.chapter_number == 7f }.manga_id)
    }

    @Test
    fun `collapses scanlator duplicates when gap-filling`() {
        val trunk = listOf(chapter(1L, 1f), chapter(1L, 2f))
        // Number 3 appears twice (two scanlators) in the gap-filled source.
        val other = listOf(chapter(2L, 3f, "A"), chapter(2L, 3f, "B"), chapter(2L, 4f))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        assertEquals(1, unified.count { it.chapter_number == 3f })
        assertEquals(listOf(1f, 2f, 3f, 4f), unified.numbers())
    }

    @Test
    fun `collapses the trunk's own scanlator variants to one row per number`() {
        // A unified list shows one row per chapter number, so the trunk's two scanlator copies of
        // chapter 1 collapse to one (single-source titles keep their variants; that path is untouched).
        val trunk = listOf(chapter(1L, 1f, "A"), chapter(1L, 1f, "B"), chapter(1L, 2f))
        val other = listOf(chapter(2L, 1f), chapter(2L, 2f))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        assertEquals(1, unified.count { it.chapter_number == 1f })
        assertEquals(listOf(1f, 2f), unified.numbers())
        assertEquals(listOf(1L), unified.map { it.manga_id }.distinct())
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1f), chapter(1L, 2f))
        val other = listOf(chapter(2L, -1f), chapter(2L, 3f))

        val unified = ChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        // The unrecognized (-1) sibling chapter can't be matched by number, so it's dropped; 3 fills.
        assertEquals(listOf(1f, 2f, 3f), unified.numbers())
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1f, "A"), chapter(1L, 1f, "B"), chapter(1L, -1f))

        val unified = ChapterAggregation.aggregate(mapOf(1L to only))

        assertEquals(only, unified)
    }

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList<Chapter>(), ChapterAggregation.aggregate(emptyMap()))
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer distinct numbers`() {
        // Source 1 has more distinct numbers, but source 2's source id is preferred.
        val source1 = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f), chapter(1L, 4f), chapter(1L, 5f))
        val source2 = listOf(chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(200L),
        )

        // Source 2 wins the trunk (1..3); source 1 only gap-fills 4 and 5.
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), unified.numbers())
        assertEquals(2L, unified.first { it.chapter_number == 1f }.manga_id)
        assertEquals(1L, unified.first { it.chapter_number == 4f }.manga_id)
    }

    @Test
    fun `multiple preferred sources order by list index, not distinct count`() {
        val sourceA = listOf(chapter(1L, 1f), chapter(1L, 2f))
        val sourceB = listOf(chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f))
        val sourceC = listOf(chapter(3L, 1f), chapter(3L, 2f), chapter(3L, 3f), chapter(3L, 4f))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L, 3L to 300L),
            // B is ranked first, then A; C is unranked (most distinct, but lowest priority).
            preferredSourceIds = listOf(200L, 100L),
        )

        // B (rank 0) is the trunk owning 1..3; C only gap-fills 4 despite having the most chapters.
        assertEquals(listOf(1f, 2f, 3f, 4f), unified.numbers())
        assertEquals(2L, unified.first { it.chapter_number == 1f }.manga_id)
        assertEquals(3L, unified.first { it.chapter_number == 4f }.manga_id)
    }

    @Test
    fun `a preferred id not present in the group falls back to distinct count`() {
        val source1 = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f), chapter(1L, 4f), chapter(1L, 5f))
        val source2 = listOf(chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f))

        val unified = ChapterAggregation.aggregate(
            chaptersBySource = mapOf(1L to source1, 2L to source2),
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = listOf(999L), // no source in the group has this id
        )

        // Unchanged from the no-preference case: source 1 (most distinct) is the trunk.
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), unified.numbers())
        assertEquals(listOf(1L), unified.map { it.manga_id }.distinct())
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f))
        val source2 = listOf(chapter(2L, 3f), chapter(2L, 4f))
        val bySource = mapOf(1L to source1, 2L to source2)

        val withDefaults = ChapterAggregation.aggregate(bySource)
        val withEmptyPrefs = ChapterAggregation.aggregate(
            chaptersBySource = bySource,
            sourceIdByManga = mapOf(1L to 100L, 2L to 200L),
            preferredSourceIds = emptyList(),
        )

        assertEquals(withDefaults, withEmptyPrefs)
    }
}
