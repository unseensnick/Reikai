package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.presentation.reader.NovelPaging.Page
import reikai.presentation.reader.NovelPaging.Turn

class NovelSpreadsTest {

    private fun text(count: Int) = (0 until count).map { Page(it * 100f, it * 100f + 90f) }

    /** A seam's page, then [count] text pages below it. */
    private fun seamed(count: Int) = listOf(Page(0f, 50f, seam = true)) + text(count).map {
        Page(it.top + 100f, it.bottom + 100f)
    }

    @ParameterizedTest
    @CsvSource("0,0", "1,0", "2,2", "3,2", "4,4")
    fun `text pages pair from the chapter's first`(index: Int, left: Int) {
        NovelSpreads.leftOf(text(5), index) shouldBe left
    }

    @ParameterizedTest
    @CsvSource("0,0", "1,1", "2,1", "3,3", "4,3")
    fun `a seam's page stands alone and the text pairs from the page below it`(index: Int, left: Int) {
        NovelSpreads.leftOf(seamed(4), index) shouldBe left
    }

    @Test
    fun `the right page is the one after the left`() {
        NovelSpreads.partnerOf(text(5), 2) shouldBe 3
    }

    @Test
    fun `a chapter ending on a left page has no right page`() {
        NovelSpreads.partnerOf(text(5), 4) shouldBe null
    }

    @Test
    fun `a seam's page has no right page`() {
        NovelSpreads.partnerOf(seamed(4), 0) shouldBe null
    }

    @Test
    fun `a spread reads to its right page`() {
        NovelSpreads.readTo(text(4), 2) shouldBe 3
    }

    @Test
    fun `a spread with no right page reads to its left`() {
        NovelSpreads.readTo(text(5), 4) shouldBe 4
    }

    @Test
    fun `a turn forward skips the right page`() {
        NovelSpreads.turn(listOf(1L), 1L, text(6), 2, forward = true) shouldBe Turn.Within(1L, 4)
    }

    @Test
    fun `a turn back lands on the spread before`() {
        NovelSpreads.turn(listOf(1L), 1L, text(6), 4, forward = false) shouldBe Turn.Within(1L, 2)
    }

    @Test
    fun `a turn from the right page moves the whole spread`() {
        NovelSpreads.turn(listOf(1L), 1L, text(6), 3, forward = true) shouldBe Turn.Within(1L, 4)
    }

    @Test
    fun `a turn forward from the seam lands on the first text spread`() {
        NovelSpreads.turn(listOf(1L), 1L, seamed(4), 0, forward = true) shouldBe Turn.Within(1L, 1)
    }

    @Test
    fun `a turn back from the first text spread lands on the seam`() {
        NovelSpreads.turn(listOf(1L), 1L, seamed(4), 1, forward = false) shouldBe Turn.Within(1L, 0)
    }

    @Test
    fun `a turn forward from the last spread opens the next chapter`() {
        NovelSpreads.turn(listOf(1L, 2L), 1L, text(4), 2, forward = true) shouldBe Turn.FirstOf(2L)
    }

    @Test
    fun `a turn back from the first spread opens the chapter before at its end`() {
        NovelSpreads.turn(listOf(1L, 2L), 2L, text(4), 1, forward = false) shouldBe Turn.LastOf(1L)
    }

    @Test
    fun `a turn past the window steps chapters`() {
        NovelSpreads.turn(listOf(1L), 1L, text(4), 2, forward = true) shouldBe Turn.Beyond(true)
    }

    @ParameterizedTest
    @CsvSource("NEVER,true,false", "NEVER,false,false", "ALWAYS,false,true", "WIDE,true,true", "WIDE,false,false")
    fun `the setting shows a spread as manga's does`(setting: DualPageView, wide: Boolean, spread: Boolean) {
        setting.showsTwoPages(width = if (wide) 2000 else 1000, height = 1500) shouldBe spread
    }

    @Test
    fun `a square screen is not wide`() {
        DualPageView.WIDE.showsTwoPages(width = 1500, height = 1500) shouldBe false
    }
}
