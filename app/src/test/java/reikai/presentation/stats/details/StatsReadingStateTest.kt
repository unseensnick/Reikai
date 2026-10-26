package reikai.presentation.stats.details

import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.presentation.stats.ReadingWindow

/** The Reading time screen's bars and list, sliced from the whole log. */
class StatsReadingStateTest {

    private val today = LocalDate(2026, 10, 19)
    private val yesterday = today.minus(1, DateTimeUnit.DAY)
    private val a: EntryId = EntryId.Manga(1L)
    private val b: EntryId = EntryId.Novel(1L)

    private val byDay = mapOf(today to mapOf(a to 100L, b to 50L), yesterday to mapOf(a to 30L))
    private val names = mapOf(a to "A", b to "B")

    @Test
    fun `a bar totals its day's reading across series`() {
        state(selected = null).bars.last().ms shouldBe 150L
    }

    @Test
    fun `a picked bar narrows the list to its dates`() {
        state(selected = today).titles.map { it.entryId to it.readMs } shouldBe listOf(a to 100L, b to 50L)
    }

    private fun state(selected: LocalDate?) =
        readingState(byDay, names, ReadingWindow.week(today), selected, emptyMap(), today)
}
