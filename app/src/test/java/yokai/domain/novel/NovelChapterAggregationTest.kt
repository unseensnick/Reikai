package yokai.domain.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import yokai.domain.novel.models.NovelChapter

class NovelChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(novelId: Long, number: Float): NovelChapter =
        NovelChapter(
            id = nextId++,
            novelId = novelId,
            url = "/$novelId/$number/$nextId",
            name = "Chapter $number",
            read = false,
            bookmark = false,
            lastTextProgress = 0,
            chapterNumber = number,
            sourceOrder = nextId,
            dateFetch = 0L,
            dateUpload = 0L,
        )

    private fun List<NovelChapter>.numbers(): List<Float> = map { it.chapterNumber }.sorted()

    @Test
    fun `trunk is the source with the most distinct numbers, not the most rows`() {
        // Novel 1: 3 real chapters, each duplicated -> 6 rows, 3 distinct.
        val source1 = listOf(
            chapter(1L, 1f), chapter(1L, 1f),
            chapter(1L, 2f), chapter(1L, 2f),
            chapter(1L, 3f), chapter(1L, 3f),
        )
        // Novel 2: 5 real chapters -> 5 rows, 5 distinct.
        val source2 = listOf(
            chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f), chapter(2L, 4f), chapter(2L, 5f),
        )

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        assertEquals(5, unified.size)
        assertEquals(listOf(2L), unified.map { it.novelId }.distinct())
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), unified.numbers())
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f), chapter(1L, 4f), chapter(1L, 5f))
        val other = listOf(chapter(2L, 4f), chapter(2L, 5f), chapter(2L, 6f), chapter(2L, 7f))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), unified.numbers())
        assertEquals(1L, unified.first { it.chapterNumber == 5f }.novelId)
        assertEquals(2L, unified.first { it.chapterNumber == 6f }.novelId)
        assertEquals(2L, unified.first { it.chapterNumber == 7f }.novelId)
    }

    @Test
    fun `collapses duplicate numbers when gap-filling`() {
        val trunk = listOf(chapter(1L, 1f), chapter(1L, 2f))
        val other = listOf(chapter(2L, 3f), chapter(2L, 3f), chapter(2L, 4f))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        assertEquals(1, unified.count { it.chapterNumber == 3f })
        assertEquals(listOf(1f, 2f, 3f, 4f), unified.numbers())
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1f), chapter(1L, 2f))
        val other = listOf(chapter(2L, -1f), chapter(2L, 3f))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        assertEquals(listOf(1f, 2f, 3f), unified.numbers())
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, -1f))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to only))

        assertEquals(only, unified)
    }

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList<NovelChapter>(), NovelChapterAggregation.aggregate(emptyMap()))
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer distinct numbers`() {
        val source1 = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f), chapter(1L, 4f), chapter(1L, 5f))
        val source2 = listOf(chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = listOf("src.b"),
        )

        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), unified.numbers())
        assertEquals(2L, unified.first { it.chapterNumber == 1f }.novelId)
        assertEquals(1L, unified.first { it.chapterNumber == 4f }.novelId)
    }

    @Test
    fun `multiple preferred sources order by list index, not distinct count`() {
        val sourceA = listOf(chapter(1L, 1f), chapter(1L, 2f))
        val sourceB = listOf(chapter(2L, 1f), chapter(2L, 2f), chapter(2L, 3f))
        val sourceC = listOf(chapter(3L, 1f), chapter(3L, 2f), chapter(3L, 3f), chapter(3L, 4f))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b", 3L to "src.c"),
            preferredSourceIds = listOf("src.b", "src.a"),
        )

        assertEquals(listOf(1f, 2f, 3f, 4f), unified.numbers())
        assertEquals(2L, unified.first { it.chapterNumber == 1f }.novelId)
        assertEquals(3L, unified.first { it.chapterNumber == 4f }.novelId)
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1f), chapter(1L, 2f), chapter(1L, 3f))
        val source2 = listOf(chapter(2L, 3f), chapter(2L, 4f))
        val byNovel = mapOf(1L to source1, 2L to source2)

        val withDefaults = NovelChapterAggregation.aggregate(byNovel)
        val withEmptyPrefs = NovelChapterAggregation.aggregate(
            chaptersByNovel = byNovel,
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = emptyList(),
        )

        assertEquals(withDefaults, withEmptyPrefs)
    }
}
