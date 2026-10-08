package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.domain.merge.flaggedOnAnotherSource
import reikai.domain.novel.model.NovelChapter

class NovelChapterAggregationTest {

    private var nextId = 1L

    private fun chapter(
        novelId: Long,
        number: Double,
        title: String = "",
        read: Boolean = false,
    ): NovelChapter =
        NovelChapter(
            id = nextId++,
            novelId = novelId,
            url = "/$novelId/$number/$nextId",
            // A descriptive title exercises the title-based match key; a blank one ("Chapter N")
            // normalizes to empty and falls back to the recognized number.
            name = title.ifBlank { "Chapter $number" },
            read = read,
            bookmark = false,
            lastTextProgress = 0,
            chapterNumber = number,
            sourceOrder = nextId,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )

    private fun List<NovelChapter>.numbers(): List<Double> = map { it.chapterNumber }.sorted()

    @Test
    fun `trunk is the source with the most chapters`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0), chapter(2L, 4.0), chapter(2L, 5.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to source1, 2L to source2)).chapters

        unified.size shouldBe 5
        unified.map { it.novelId }.distinct() shouldBe listOf(2L)
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
    }

    @Test
    fun `gap-fills numbers the trunk is missing from other sources`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val other = listOf(chapter(2L, 4.0), chapter(2L, 5.0), chapter(2L, 6.0), chapter(2L, 7.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)
        unified.first { it.chapterNumber == 5.0 }.novelId shouldBe 1L
        unified.first { it.chapterNumber == 6.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 7.0 }.novelId shouldBe 2L
    }

    @Test
    fun `collapses duplicate numbers when gap-filling`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val other = listOf(chapter(2L, 3.0), chapter(2L, 3.0), chapter(2L, 4.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.count { it.chapterNumber == 3.0 } shouldBe 1
        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `drops sibling chapters with an unrecognized number`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        // A negative number is the recognizer finding none, so it can't be matched across sources.
        val other = listOf(chapter(2L, -1.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0)
    }

    @Test
    fun `keeps a sibling's untitled chapter 0, which is a recognized number`() {
        val trunk = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val other = listOf(chapter(2L, 0.0), chapter(2L, 1.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.numbers() shouldBe listOf(0.0, 1.0, 2.0, 3.0)
    }

    // Cross-source read carry-over, the twin of MergedChapterProviderTest's manga cases. Run over the
    // shared kernel, against the units this stitch produced, which is what every surface reads.

    private fun readInOtherSources(byNovel: Map<Long, List<NovelChapter>>): Set<Long> {
        val merged = NovelChapterAggregation.merge(byNovel)
        return flaggedOnAnotherSource(byNovel.values.flatten(), merged.chapters, merged.units, { it.id }, { it.read })
    }

    @Test
    fun `a chapter read on another source is reported as read for the group`() {
        val trunk = listOf(chapter(1L, 1.0, "Terminal"))
        val other = listOf(chapter(2L, 1.0, "Terminal", read = true))
        val byNovel = mapOf(1L to trunk, 2L to other)

        val result = readInOtherSources(byNovel)

        result shouldBe setOf(trunk.single().id)
    }

    @Test
    fun `a chapter nobody has read is not reported`() {
        val byNovel = mapOf(1L to listOf(chapter(1L, 1.0, "Terminal")), 2L to listOf(chapter(2L, 1.0, "Terminal")))

        val result = readInOtherSources(byNovel)

        result shouldBe emptySet()
    }

    @Test
    fun `an unmerged novel reports nothing, even where two of its own rows match`() {
        // One source can hold two rows with the same title. Reading one is not reading the other, and
        // only another SOURCE having read it counts.
        val byNovel = mapOf(
            1L to listOf(
                chapter(1L, 1.0, "Terminal", read = true),
                chapter(1L, 1.0, "Terminal"),
            ),
        )

        val result = readInOtherSources(byNovel)

        result shouldBe emptySet()
    }

    @Test
    fun `a different chapter read on another source is not reported`() {
        val trunk = listOf(chapter(1L, 1.0, "Terminal"))
        val other = listOf(chapter(2L, 2.0, "New Arc", read = true))
        val byNovel = mapOf(1L to trunk, 2L to other)

        val result = readInOtherSources(byNovel)

        result shouldBe emptySet()
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

        val unified = NovelChapterAggregation.merge(mapOf(1L to source1, 2L to source2)).chapters

        // 3 distinct chapters by title; the disagreeing numbers don't create duplicates.
        unified.size shouldBe 3
        unified.map { it.name.lowercase() }.toSet() shouldBe setOf("surviving just to die", "terminal", "new arc")
    }

    @Test
    fun `title match ignores a leading chapter label and number`() {
        val source1 = listOf(chapter(1L, 1.0, "Chapter 1 - 0 Surviving Just To Die"))
        val source2 = listOf(chapter(2L, 5.0, "0 Surviving Just to Die"))

        val unified = NovelChapterAggregation.merge(mapOf(1L to source1, 2L to source2)).chapters

        // Both normalize to "surviving just to die", so they collapse to one row.
        unified.size shouldBe 1
    }

    @Test
    fun `keeps every trunk chapter even when two share a title`() {
        // The novel stitch collapses no source's own rows, so two distinct trunk chapters with the same
        // title text must both survive (the bug was collapsing the trunk against itself).
        val trunk = listOf(
            chapter(1L, 1.0, "Interlude"),
            chapter(1L, 2.0, "Story"),
            chapter(1L, 3.0, "Interlude"),
        )
        val other = listOf(chapter(2L, 1.0, "Interlude"))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        // All 3 trunk chapters kept; the sibling "Interlude" repeats a key, so it's dropped.
        unified.size shouldBe 3
        unified.map { it.novelId }.distinct() shouldBe listOf(1L)
        unified.count { it.name == "Interlude" } shouldBe 2
    }

    @Test
    fun `keeps a sibling sequel chapter that differs only by a trailing number`() {
        // "Pleasureful Repeats 2" must stay distinct from "Pleasureful Repeats": the trailing number is
        // part of the title, so a sibling's sequel chapter must survive gap-fill, not be deduped out (the
        // bug that showed the chapter present per-source but "missing" in the unified view).
        val trunk = listOf(
            chapter(1L, 1.0, "Pleasureful Repeats"),
            chapter(1L, 2.0, "Missing Book"),
            chapter(1L, 3.0, "Trouble Itself"),
        )
        val other = listOf(chapter(2L, 1.0, "Pleasureful Repeats"), chapter(2L, 1.5, "Pleasureful Repeats 2"))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        // The sibling's "Pleasureful Repeats" repeats the trunk's key and drops; "Pleasureful Repeats 2"
        // keys distinctly and is gap-filled in.
        unified.map { it.name }.toSet() shouldBe
            setOf("Pleasureful Repeats", "Missing Book", "Trouble Itself", "Pleasureful Repeats 2")
        unified.size shouldBe 4
    }

    @Test
    fun `unnumbered novels show the fullest source's full list unchanged`() {
        // Both sources leave every chapter unnumbered (-1.0 once a sync finds no number), the common
        // lnreader case. There's no cross-source key, so the unified view is just the fullest source.
        val small = listOf(chapter(1L, -1.0), chapter(1L, -1.0), chapter(1L, -1.0))
        val big = listOf(chapter(2L, -1.0), chapter(2L, -1.0), chapter(2L, -1.0), chapter(2L, -1.0), chapter(2L, -1.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to small, 2L to big)).chapters

        unified shouldBe big
        unified.map { it.novelId }.distinct() shouldBe listOf(2L)
    }

    @Test
    fun `single source returns its chapters unchanged`() {
        val only = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 0.0))

        val unified = NovelChapterAggregation.merge(mapOf(1L to only)).chapters

        unified shouldBe only
    }

    @Test
    fun `empty input returns empty`() {
        NovelChapterAggregation.merge(emptyMap()).chapters shouldBe emptyList()
    }

    @Test
    fun `a preferred source becomes the trunk even with fewer chapters`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.merge(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = listOf("src.b"),
        ).chapters

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 1L
    }

    @Test
    fun `multiple preferred sources order by list index, not chapter count`() {
        val sourceA = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val sourceB = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val sourceC = listOf(chapter(3L, 1.0), chapter(3L, 2.0), chapter(3L, 3.0), chapter(3L, 4.0))

        val unified = NovelChapterAggregation.merge(
            chaptersByNovel = mapOf(1L to sourceA, 2L to sourceB, 3L to sourceC),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b", 3L to "src.c"),
            preferredSourceIds = listOf("src.b", "src.a"),
        ).chapters

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 3L
    }

    @Test
    fun `empty preferred list is identical to the no-argument call`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 3.0), chapter(2L, 4.0))
        val byNovel = mapOf(1L to source1, 2L to source2)

        val withDefaults = NovelChapterAggregation.merge(byNovel).chapters
        val withEmptyPrefs = NovelChapterAggregation.merge(
            chaptersByNovel = byNovel,
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = emptyList(),
        ).chapters

        withDefaults shouldBe withEmptyPrefs
    }

    @Test
    fun `member ranking makes the chosen member the trunk over chapter count`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0), chapter(1L, 4.0), chapter(1L, 5.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))

        val unified = NovelChapterAggregation.merge(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            memberRanking = listOf(2L, 1L),
        ).chapters

        unified.numbers() shouldBe listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
        unified.first { it.chapterNumber == 4.0 }.novelId shouldBe 1L
    }

    @Test
    fun `member ranking overrides the preferred-source list`() {
        val source1 = listOf(chapter(1L, 1.0), chapter(1L, 2.0), chapter(1L, 3.0))
        val source2 = listOf(chapter(2L, 1.0), chapter(2L, 2.0))

        val unified = NovelChapterAggregation.merge(
            chaptersByNovel = mapOf(1L to source1, 2L to source2),
            sourceIdByNovel = mapOf(1L to "src.a", 2L to "src.b"),
            preferredSourceIds = listOf("src.a"), // would rank member 1 first
            memberRanking = listOf(2L, 1L), // but the per-group override wins
        ).chapters

        unified.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
    }

    @Test
    fun `member ranking orders two members that share one source`() {
        // Two library rows from the same source in one group: a source-id ranking cannot tell them
        // apart, so the override must rank by member id.
        val first = listOf(chapter(1L, 1.0), chapter(1L, 2.0))
        val second = listOf(chapter(2L, 1.0), chapter(2L, 2.0), chapter(2L, 3.0))
        val byNovel = mapOf(1L to first, 2L to second)
        val sameSource = mapOf(1L to "src.a", 2L to "src.a")

        val member1First = NovelChapterAggregation.merge(byNovel, sameSource, memberRanking = listOf(1L, 2L)).chapters
        member1First.first { it.chapterNumber == 1.0 }.novelId shouldBe 1L
        member1First.first { it.chapterNumber == 3.0 }.novelId shouldBe 2L

        val member2First = NovelChapterAggregation.merge(byNovel, sameSource, memberRanking = listOf(2L, 1L)).chapters
        member2First.first { it.chapterNumber == 1.0 }.novelId shouldBe 2L
    }

    @Test
    fun `two sources counting differently still come out in reading order`() {
        // The shape that broke: both sources number by their own site's position, so the same chapter
        // is 11 on one and 1 on the other. Only the titles agree, and the order must follow them.
        val trunk = listOf(
            chapter(1L, 11.0, "Alpha"),
            chapter(1L, 12.0, "Bravo"),
            chapter(1L, 13.0, "Charlie"),
        )
        val other = listOf(
            chapter(2L, 1.0, "Alpha"),
            chapter(2L, 2.0, "Bravo"),
            chapter(2L, 3.0, "Charlie"),
            chapter(2L, 4.0, "Delta"),
        )

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.map { it.name } shouldBe listOf("Alpha", "Bravo", "Charlie", "Delta")
        unified.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }

    @Test
    fun `a source sharing no chapter with the trunk follows it`() {
        val trunk = listOf(chapter(1L, 1.0, "Alpha"), chapter(1L, 2.0, "Bravo"), chapter(1L, 3.0, "Charlie"))
        val other = listOf(chapter(2L, 1.0, "Delta"), chapter(2L, 2.0, "Echo"))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.map { it.name } shouldBe listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")
    }

    @Test
    fun `a chapter only the other source has lands between its neighbours`() {
        val trunk = listOf(chapter(1L, 1.0, "Alpha"), chapter(1L, 3.0, "Charlie"))
        val other = listOf(chapter(2L, 1.0, "Alpha"), chapter(2L, 2.0, "Bravo"), chapter(2L, 3.0, "Charlie"))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).chapters

        unified.map { it.name } shouldBe listOf("Alpha", "Bravo", "Charlie")
    }

    /** Two volumes that each close on an afterword, whose titles normalize to the same identity. */
    private fun volumes(novelId: Long) = listOf(
        chapter(novelId, 1.0, "Vol 1 Ch 1 Arrival"),
        chapter(novelId, 2.0, "Vol 1 Afterword"),
        chapter(novelId, 3.0, "Vol 2 Ch 1 Journey"),
        chapter(novelId, 4.0, "Vol 2 Afterword"),
        chapter(novelId, 5.0, "Vol 3 Ch 1 End"),
    )

    @Test
    fun `a title repeated per volume pairs each copy with its own volume`() {
        val trunk = volumes(1L)
        val other = volumes(2L)

        val unitOf = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other)).units
            .associate { it.chapterId to it.unit }

        unitOf[other[3].id] shouldBe unitOf[trunk[3].id]
    }

    @Test
    fun `a chapter only the other source has after a repeated title lands in its own volume`() {
        val trunk = volumes(1L)
        val other = volumes(2L).toMutableList().apply { add(4, chapter(2L, 4.5, "Vol 2 Bonus Story")) }

        val unified = NovelChapterAggregation.merge(
            mapOf(1L to trunk, 2L to other),
            memberRanking = listOf(1L, 2L),
        ).chapters

        unified.map { it.name } shouldBe listOf(
            "Vol 1 Ch 1 Arrival",
            "Vol 1 Afterword",
            "Vol 2 Ch 1 Journey",
            "Vol 2 Afterword",
            "Vol 2 Bonus Story",
            "Vol 3 Ch 1 End",
        )
    }

    /** The merged chapter each of [chapters] landed in, trunk-first ranking. */
    private fun unitsOf(
        trunk: List<NovelChapter>,
        other: List<NovelChapter>,
        chapters: List<NovelChapter>,
    ): List<Int?> {
        val unitOf = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other), memberRanking = listOf(1L, 2L))
            .units.associate { it.chapterId to it.unit }
        return chapters.map { unitOf[it.id] }
    }

    @Test
    fun `a titled run pairs with the trunk's run of number-only names between the same chapters`() {
        // One site names a chapter "685 Chapter 685", which carries no title text, the other titles it.
        val trunk = listOf(
            chapter(1L, 684.0, "Alpha"),
            chapter(1L, 685.0, "685 Chapter 685"),
            chapter(1L, 686.0, "686 Chapter 686"),
            chapter(1L, 687.0, "Bravo"),
        )
        val other = listOf(
            chapter(2L, 684.0, "Alpha"),
            chapter(2L, 685.0, "CH.685 Lessons at the Spire"),
            chapter(2L, 686.0, "CH.686 Departure"),
            chapter(2L, 687.0, "Bravo"),
        )

        unitsOf(trunk, other, other) shouldBe unitsOf(trunk, other, trunk)
    }

    @Test
    fun `a title repeated inside such a run does not pair with its earlier namesake`() {
        // The sibling already supplied the earlier "Infiltration", so its later one is a new chapter
        // of the run, never a second copy of chapter 2.
        val trunk = listOf(
            chapter(1L, 1.0, "Alpha"),
            chapter(1L, 2.0, "Infiltration"),
            chapter(1L, 3.0, "Bravo"),
            chapter(1L, 685.0, "685 Chapter 685"),
            chapter(1L, 686.0, "686 Chapter 686"),
            chapter(1L, 687.0, "687 Chapter 687"),
            chapter(1L, 688.0, "Omega"),
        )
        val other = listOf(
            chapter(2L, 1.0, "Alpha"),
            chapter(2L, 2.0, "Infiltration"),
            chapter(2L, 3.0, "Bravo"),
            chapter(2L, 685.0, "CH.685 Lessons at the Spire"),
            chapter(2L, 686.0, "CH.686 Infiltration"),
            chapter(2L, 687.0, "CH.687 Departure"),
            chapter(2L, 688.0, "Omega"),
        )

        unitsOf(trunk, other, other) shouldBe unitsOf(trunk, other, trunk)
    }

    @Test
    fun `a title still matches a chapter its source only paired by position`() {
        // The sibling numbers two ahead, so its "Chapter 1" pairs with "Echoes" by position first.
        val trunk = listOf(
            chapter(1L, 1.0, "Alpha"),
            chapter(1L, 2.0, "Echoes"),
            chapter(1L, 3.0, "Chapter 3"),
            chapter(1L, 4.0, "Bravo"),
        )
        val other = listOf(
            chapter(2L, 1.0, "Alpha"),
            chapter(2L, 9.0, "Chapter 1"),
            chapter(2L, 3.0, "Chapter 3"),
            chapter(2L, 4.0, "Echoes"),
            chapter(2L, 5.0, "Bravo"),
        )

        unitsOf(trunk, other, other.filter { it.name == "Echoes" }) shouldBe
            unitsOf(trunk, other, trunk.filter { it.name == "Echoes" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["Court's", "Court\u2019s", "Court\u201Cs", "Court\uFFFDs", "Cou\u200Brts"])
    fun `a title matches across an apostrophe one source drops`(spelling: String) {
        // One site writes "Heavenly Courts Crisis", another the possessive, which once split into "court s".
        val trunk = listOf(chapter(1L, 1.0, "Alpha"), chapter(1L, 2.0, "Heavenly Courts Crisis"))
        val other = listOf(chapter(2L, 1.0, "Alpha"), chapter(2L, 2.0, "2: Heavenly $spelling Crisis"))

        unitsOf(trunk, other, other) shouldBe unitsOf(trunk, other, trunk)
    }

    @Test
    fun `a source repeating a title far apart pairs only its nearer copy with the trunk's`() {
        // The trunk misspells the earlier chapter, so the sibling's early "Into the Storm" has no
        // counterpart; matching it to the trunk's later one gave that chapter two of the sibling's.
        val trunk = listOf(
            chapter(1L, 1.0, "Alpha"),
            chapter(1L, 2.0, "lnto the Storm"),
            chapter(1L, 3.0, "Bravo"),
            chapter(1L, 4.0, "Charlie"),
            chapter(1L, 5.0, "Delta"),
            chapter(1L, 6.0, "Into the Storm"),
            chapter(1L, 7.0, "Echo"),
        )
        val other = trunk.map { chapter(2L, it.chapterNumber, it.name.replace("lnto", "Into")) }

        unitsOf(trunk, other, other).distinct().size shouldBe other.size
    }

    @Test
    fun `a number-only name pairs by the number it shows, not the one its source stored`() {
        // One site stores its own list position, two past the chapter its name shows.
        val names = listOf("Alpha", "Chapter 2", "Chapter 3", "Chapter 4: Bravo", "Chapter 5", "Charlie")
        val trunk = names.mapIndexed { index, name -> chapter(1L, index + 1.0, name) }
        val other = names.mapIndexed { index, name -> chapter(2L, index + 3.0, name) }

        unitsOf(trunk, other, other) shouldBe unitsOf(trunk, other, trunk)
    }

    @Test
    fun `two different titles between the same chapters never pair`() {
        val trunk = listOf(chapter(1L, 1.0, "Alpha"), chapter(1L, 2.0, "Interlude"), chapter(1L, 3.0, "Bravo"))
        val other = listOf(chapter(2L, 1.0, "Alpha"), chapter(2L, 2.0, "Side Story"), chapter(2L, 3.0, "Bravo"))

        val unified = NovelChapterAggregation.merge(mapOf(1L to trunk, 2L to other), memberRanking = listOf(1L, 2L))

        unified.chapters.map { it.name } shouldBe listOf("Alpha", "Side Story", "Interlude", "Bravo")
    }
}
