package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter

class NovelChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(novelId: Long, number: Double, title: String = ""): NovelChapter =
        NovelChapter(
            id = nextId++,
            novelId = novelId,
            url = "/$novelId/$number/$nextId",
            // A descriptive title exercises the title-based match key; a blank one ("Chapter N")
            // normalizes to empty and falls back to the recognized number.
            name = title.ifBlank { "Chapter $number" },
            read = false,
            bookmark = false,
            lastTextProgress = 0,
            chapterNumber = number,
            sourceOrder = nextId,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
            isDownloaded = false,
        )

    private fun List<NovelChapter>.numbers(): List<Double> = map { it.chapterNumber }.sorted()

    @Test
    fun `trunk is the source with the most chapters`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0), chapter(2L, 4.0), chapter(2L, 5.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        unified.size shouldBe 5
        unified.map { it.novelId }.distinct() shouldBe listOf(2L)
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val other = listOf(chapter(2L, 4.0), chapter(2L, 5.0), chapter(2L, 6.0), chapter(2L, 7.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        unified.first { it.chapterNumber == 5.0 }.novelId shouldBe 1L
        unified.first { it.chapterNumber == 6.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 7.0 }.novelId shouldBe 2L
    }

    @Test
    fun `collapses duplicate numbers when gap-filling`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val other = listOf(chapter(2L, 3.0), chapter(2L, 3.0), chapter(2L, 4.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.count { it.chapterNumber == 3.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        // 0.0 means "no number" for novels, so it can't be matched across sources.
        val other = listOf(chapter(2L, 0.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0)
    }

    @Test
    fun `matches chapters across sources by title when numbers disagree`() {
        // Same chapters, off-by-one numbering across sources, but identical title text.
        val source1 = listOf(chapter(1L, 1.0, "Surviving Just To Die"), chapter(1L, 2.0, "Terminal"))
        val source2 = listOf(
            chapter(2L, 0.0, "surviving just to die"),
            chapter(2L, 1.0, "Terminal"),
            chapter(2L, 2.0, "New Arc"),
        )

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // 3 distinct chapters by title; the disagreeing numbers don't create duplicates.
        unified.size shouldBe 3
        unified.map { it.name.lowercase() }.toSet() shouldBe setOf("surviving just to die", "terminal", "new arc")
    }

    @Test
    fun `title match ignores a leading chapter label and number`() {
        val source1 = listOf(chapter(1L, 1.0, "Chapter 1 - 0 Surviving Just To Die"))
        val source2 = listOf(chapter(2L, 5.0, "0 Surviving Just to Die"))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to source1, 2L to source2))

        // Both normalize to "surviving just to die", so they collapse to one row.
        unified.size shouldBe 1
    }

    @Test
    fun `keeps every trunk chapter even when two share a title`() {
        // Novels have no scanlator variants, so two distinct trunk chapters with the same title text
        // must both survive (the bug was collapsing the trunk against itself).
        val trunk = listOf(
            chapter(1L, 1.0, "Interlude"),
            chapter(1L, 2.0, "Story"),
            chapter(1L, 3.0, "Interlude"),
        )
        val other = listOf(chapter(2L, 1.0, "Interlude"))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to trunk, 2L to other))

        // All 3 trunk chapters kept; the sibling "Interlude" repeats a key, so it's dropped.
        unified.size shouldBe 3
        unified.map { it.novelId }.distinct() shouldBe listOf(1L)
        unified.count { it.name == "Interlude" } shouldBe 2
    }

    @Test
    fun `unnumbered novels show the fullest source's full list unchanged`() {
        // Both sources leave every chapter unnumbered (0.0), the common lnreader case. There's no
        // cross-source key, so the unified view is just the source with the most chapters.
        val small = listOf(chapter(1L, 0.0), chapter(1L, 0.0), chapter(1L, 0.0))
        val big = listOf(chapter(2L, 0.0), chapter(2L, 0.0), chapter(2L, 0.0), chapter(2L, 0.0), chapter(2L, 0.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to small, 2L to big))

        unified shouldBe big
        unified.map { it.novelId }.distinct() shouldBe listOf(2L)
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 0.0))

        val unified = NovelChapterAggregation.aggregate(mapOf(1L to only))

        unified shouldBe only
    }

    @Test
    fun `empty input returns empty`() {
        NovelChapterAggregation.aggregate(emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer chapters`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = listOf("src.b"),
        )

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 1L
    }

    @Test
    fun `multiple preferred sources order by list index, not chapter count`() {
        val sourceA = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val sourceB = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val sourceC = listOf(chapter(3L, 1.0), chapter(3L, 2.0), chapter(3L, 3.0), chapter(3L, 4.0))

        val unified = NovelChapterAggregation.aggregate(
            chaptersByNovel = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b", 3L to "src.c"),
            preferredSourceIds = listOf("src.b", "src.a"),
        )

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 3L
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))
        val byNovel = mapOf(1L to source1, 2L to source2)

        val withDefaults = NovelChapterAggregation.aggregate(byNovel)
        val withEmptyPrefs = NovelChapterAggregation.aggregate(
            chaptersByNovel = byNovel,
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = emptyList(),
        )

        withDefaults shouldBe withEmptyPrefs
    }
}
