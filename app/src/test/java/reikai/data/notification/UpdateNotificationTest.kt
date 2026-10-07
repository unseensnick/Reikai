package reikai.data.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

/** The per-entry row and progress rules both library updaters (and the gallery checker's progress) share. */
class UpdateNotificationTest {

    @Test
    fun `a hidden entry is only counted, even with numbered chapters`() {
        newChaptersEntry(shownTitle = null, chapterNumbers = listOf(1.0, 2.0), total = 2) shouldBe
            NewChaptersEntry(title = null, chapters = NewChapters.Count(2))
    }

    @Test
    fun `a shown entry names its chapters under its chopped title`() {
        newChaptersEntry("x".repeat(60), listOf(1.0, 2.0), total = 2) shouldBe
            NewChaptersEntry("x".repeat(NOTIF_TITLE_MAX_LEN - 1) + "…", NewChapters.Multiple(listOf("1", "2"), 0))
    }

    @Test
    fun `hidden content posts no per-entry rows`() {
        postedEntries(listOf("a", "b"), hideAll = true) shouldBe emptyList()
    }

    @Test
    fun `past the cap the rest get no row`() {
        postedEntries((1..25).toList(), hideAll = false) shouldBe (1..20).toList()
    }

    @Test
    fun `two of three reads 66 percent, rounding down`() {
        updateProgressPercent(2, 3, Locale.US) shouldBe "66%"
    }

    @Test
    fun `seven of ten reads 70 percent`() {
        updateProgressPercent(7, 10, Locale.US) shouldBe "70%"
    }

    @Test
    fun `twenty-nine of a hundred reads 29 percent`() {
        updateProgressPercent(29, 100, Locale.US) shouldBe "29%"
    }
}
