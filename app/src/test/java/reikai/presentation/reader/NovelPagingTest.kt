package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.presentation.reader.NovelPaging.Band
import reikai.presentation.reader.NovelPaging.Page
import reikai.presentation.reader.NovelPaging.Turn

class NovelPagingTest {

    /** Ten lines of 30 from 10 down, the shape a chapter's padding then its text has. */
    private val lines = (0 until 10).map { Band(10f + it * 30f, 40f + it * 30f) }

    @Test
    fun `a page takes whole lines until the next would overflow it`() {
        NovelPaging.cut(lines, height = 100f).first() shouldBe Page(10f, 100f)
    }

    @Test
    fun `the next page starts on the first line the last one left out`() {
        NovelPaging.cut(lines, height = 100f)[1] shouldBe Page(100f, 190f)
    }

    @Test
    fun `every line lands on a page`() {
        NovelPaging.cut(lines, height = 100f).last() shouldBe Page(280f, 310f)
    }

    @Test
    fun `fragments of one line merge into one band`() {
        val fragments = listOf(Band(0f, 30f), Band(5f, 32f), Band(31.5f, 60f))
        NovelPaging.cut(fragments, height = 40f) shouldBe listOf(Page(0f, 32f), Page(31.5f, 60f))
    }

    /** Unmerged, the second page would start inside the first line and show its foot again. */
    @Test
    fun `a fragment overflowing the page never starts a page inside the line it belongs to`() {
        val tallFragment = listOf(Band(0f, 30f), Band(25f, 70f))
        NovelPaging.cut(tallFragment, height = 60f) shouldBe listOf(Page(0f, 60f), Page(60f, 70f))
    }

    @Test
    fun `a band taller than a page starts a page and is cut at page height`() {
        val picture = listOf(Band(0f, 30f), Band(30f, 280f), Band(280f, 310f))
        NovelPaging.cut(picture, height = 100f) shouldBe
            listOf(Page(0f, 30f), Page(30f, 130f), Page(130f, 230f), Page(230f, 310f))
    }

    @Test
    fun `the line after a cut picture is whole`() {
        val picture = listOf(Band(0f, 250f), Band(250f, 290f))
        NovelPaging.cut(picture, height = 100f).last() shouldBe Page(200f, 290f)
    }

    @Test
    fun `a band marked alone is a page of its own`() {
        val seamThenText = listOf(Band(0f, 40f, alone = true), Band(40f, 60f), Band(60f, 80f))
        NovelPaging.cut(seamThenText, height = 100f) shouldBe listOf(Page(0f, 40f), Page(40f, 80f))
    }

    @Test
    fun `a page with no room for a line is no pages`() {
        NovelPaging.cut(lines, height = 0f) shouldBe emptyList()
    }

    @Test
    fun `nothing measured is no pages`() {
        NovelPaging.cut(emptyList(), height = 100f) shouldBe emptyList()
    }

    @Test
    fun `a position inside a page reads that page`() {
        NovelPaging.pageAt(NovelPaging.cut(lines, 100f), y = 150f) shouldBe 1
    }

    @Test
    fun `a page's own top reads that page`() {
        NovelPaging.pageAt(NovelPaging.cut(lines, 100f), y = 100f) shouldBe 1
    }

    @Test
    fun `a position above the first page reads the first`() {
        NovelPaging.pageAt(NovelPaging.cut(lines, 100f), y = 0f) shouldBe 0
    }

    @Test
    fun `a turn forward inside a chapter moves one page`() {
        NovelPaging.turn(listOf(1L, 2L), chapterId = 1L, index = 2, count = 4, forward = true) shouldBe
            Turn.Within(1L, 3)
    }

    @Test
    fun `a turn forward off the last page opens the next chapter's first`() {
        NovelPaging.turn(listOf(1L, 2L), chapterId = 1L, index = 3, count = 4, forward = true) shouldBe
            Turn.FirstOf(2L)
    }

    @Test
    fun `a turn back off the first page opens the previous chapter's last`() {
        NovelPaging.turn(listOf(1L, 2L), chapterId = 2L, index = 0, count = 4, forward = false) shouldBe
            Turn.LastOf(1L)
    }

    @Test
    fun `a turn past the window's end leaves it`() {
        NovelPaging.turn(listOf(1L), chapterId = 1L, index = 3, count = 4, forward = true) shouldBe Turn.Beyond(true)
    }

    /** A WebView chapter's script can answer the measure; a page under a pixel never ended cutting. */
    @Test
    fun `a page shorter than a pixel has no pages`() {
        NovelPaging.cut(listOf(Band(0f, 1_000_000f)), height = 1e-6f) shouldBe emptyList()
    }

    @ParameterizedTest
    @ValueSource(floats = [Float.NaN, Float.POSITIVE_INFINITY])
    fun `a page height that is not a finite number has no pages`(height: Float) {
        NovelPaging.cut(listOf(Band(0f, 40f)), height = height) shouldBe emptyList()
    }

    @Test
    fun `a band too tall for any chapter stops at the page cap`() {
        NovelPaging.cut(listOf(Band(0f, Float.MAX_VALUE)), height = 1f).size shouldBe 10_000
    }

    @Test
    fun `a band without a finite edge is dropped`() {
        NovelPaging.cut(listOf(Band(Float.NEGATIVE_INFINITY, 40f), Band(10f, 40f)), height = 100f) shouldBe
            listOf(Page(10f, 40f))
    }

    @Test
    fun `the paged slider's page names that page`() {
        NovelPaging.indexFor(ChapterProgress.Pages(lastPageRead = 7, pageCount = 19).fraction, count = 19) shouldBe 7
    }

    @Test
    fun `the last page is all of the chapter`() {
        val pages = uniform(5)
        NovelPaging.progressOf(pages, index = 4, textTop = 0f, scrollable = scrollableOf(pages)) shouldBe
            ChapterProgress.Percent(ChapterProgress.Percent.FULL)
    }

    /** As a chapter that fits on one screen reads while scrolling, so it is not read the moment it opens. */
    @Test
    fun `a single page is the chapter's start`() {
        NovelPaging.progressOf(uniform(1), index = 0, textTop = 0f, scrollable = 0f) shouldBe
            ChapterProgress.Percent(0)
    }

    /** Hundredths of a large chapter's scroll still fall inside one of its pages, which whole percents do not. */
    @ParameterizedTest
    @ValueSource(ints = [2, 3, 10, 33, 101, 1000, 5000])
    fun `a line read while scrolling never reopens on a page after it`(count: Int) {
        val pages = uniform(count)
        val scrollable = scrollableOf(pages)
        val lines = pages.flatMap { listOf(it.top - 3f, it.top + 3f, it.top + PAGE / 2) }
            .filter { it >= 0f && it < scrollable }
        lines.filter { y ->
            val saved = ChapterProgress.Percent.of(y.toDouble() / scrollable)
            NovelPaging.landingIndex(pages, saved, textTop = 0f, scrollable) > NovelPaging.pageAt(pages, y)
        } shouldBe emptyList()
    }

    /** Holds while a hundredth of the scroll is shorter than the last full page's room, about 8,800 pages here. */
    @ParameterizedTest
    @ValueSource(ints = [2, 3, 10, 33, 101, 1000, 5000])
    fun `every page reopens on itself`(count: Int) {
        val pages = uniform(count)
        val scrollable = scrollableOf(pages)
        pages.indices.filter { index ->
            val saved = NovelPaging.progressOf(pages, index, textTop = 0f, scrollable)
            NovelPaging.landingIndex(pages, saved, textTop = 0f, scrollable) != index
        } shouldBe emptyList()
    }

    /** Past the scroll's end every position reads all of the chapter, so the page there stands for the rest. */
    @Test
    fun `a page past the scroll's end reopens no later than the page holding that end`() {
        val pages = uniform(10) + Page(10 * PAGE, 10 * PAGE + 30f)
        val scrollable = scrollableOf(pages)
        val saved = NovelPaging.progressOf(pages, index = 9, textTop = 0f, scrollable)
        NovelPaging.landingIndex(pages, saved, textTop = 0f, scrollable) shouldBe 8
    }

    /** Pages of [PAGE] from the text's top, which a screen of [PAGE] plus margins shows one at a time. */
    private fun uniform(count: Int) = List(count) { Page(it * PAGE, (it + 1) * PAGE) }

    private fun scrollableOf(pages: List<Page>) = pages.last().bottom - (PAGE + MARGINS)

    private companion object {
        const val PAGE = 1000f
        const val MARGINS = 120f
    }
}
