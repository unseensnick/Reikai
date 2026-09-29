package reikai.data.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one rule both update notifiers name their chapters by. Manga carried it alone until the novel
 * updater stopped reporting a bare count, so these cases hold for both content types.
 */
class NewChaptersTest {

    @Test
    fun `a source that numbered nothing falls back to a count`() {
        newChapters(chapterNumbers = listOf(-1.0, -1.0), total = 2) shouldBe NewChapters.Count(2)
    }

    @Test
    fun `one numbered chapter is named on its own`() {
        newChapters(listOf(3.0), total = 1) shouldBe NewChapters.Single("3", remaining = 0)
    }

    @Test
    fun `the unnumbered chapters alongside a named one are counted as remaining`() {
        newChapters(listOf(3.0, -1.0, -1.0), total = 3) shouldBe NewChapters.Single("3", remaining = 2)
    }

    @Test
    fun `several chapters are listed in reading order, not the order they arrived`() {
        newChapters(listOf(3.0, 1.0, 2.0), total = 3) shouldBe
            NewChapters.Multiple(listOf("1", "2", "3"), remaining = 0)
    }

    @Test
    fun `a fractional chapter keeps its point`() {
        newChapters(listOf(2.5, 1.0), total = 2) shouldBe
            NewChapters.Multiple(listOf("1", "2.5"), remaining = 0)
    }

    @Test
    fun `past five chapters the rest become a remainder`() {
        newChapters((1..8).map { it.toDouble() }, total = 8) shouldBe
            NewChapters.Multiple(listOf("1", "2", "3", "4", "5"), remaining = 3)
    }

    /** Two sources of a merged entry can both report chapter 1; the row must not say "1, 1". */
    @Test
    fun `a number reported twice is named once`() {
        newChapters(listOf(1.0, 1.0, 2.0), total = 3) shouldBe
            NewChapters.Multiple(listOf("1", "2"), remaining = 0)
    }

    @Test
    fun `unnumbered chapters beside several named ones are counted as remaining`() {
        newChapters(listOf(1.0, 2.0, -1.0, -1.0), total = 4) shouldBe
            NewChapters.Multiple(listOf("1", "2"), remaining = 2)
    }

    @Test
    fun `a single number reported twice is not counted as one more`() {
        newChapters(listOf(1.0, 1.0), total = 2) shouldBe NewChapters.Single("1", remaining = 0)
    }

    @Test
    fun `a summary over one shown entry names it`() {
        newChaptersSummary(listOf("Berserk"), hideAll = false) shouldBe NewChaptersSummary.One("Berserk")
    }

    @Test
    fun `a summary over one hidden entry counts it without naming it`() {
        newChaptersSummary(listOf(null), hideAll = false) shouldBe NewChaptersSummary.Many(1, listOf(null))
    }

    @Test
    fun `a summary lists every entry, a hidden one generically`() {
        newChaptersSummary(listOf("Berserk", null), hideAll = false) shouldBe
            NewChaptersSummary.Many(2, listOf("Berserk", null))
    }

    @Test
    fun `a summary with all content hidden lists no titles`() {
        newChaptersSummary(listOf(null, null), hideAll = true) shouldBe NewChaptersSummary.Many(2, emptyList())
    }

    @Test
    fun `a summary chops a long title`() {
        newChaptersSummary(listOf("x".repeat(60)), hideAll = false) shouldBe
            NewChaptersSummary.One("x".repeat(NOTIF_TITLE_MAX_LEN - 1) + "…")
    }

    @Test
    fun `fifteen new chapters still offer a download`() {
        offersDownloadAction(newChapters = 15) shouldBe true
    }

    @Test
    fun `past fifteen new chapters no download is offered`() {
        offersDownloadAction(newChapters = 16) shouldBe false
    }
}
