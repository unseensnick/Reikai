package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/** How the reading-time screen cuts its windows into bars and steps between them. */
class StatsReadingPeriodsTest {

    private val today = LocalDate(2026, 10, 19)

    @Test
    fun `the current month runs from its first to its last day`() {
        ReadingWindow.current(ReadingPeriod.MONTH, today).let { it.first to it.last } shouldBe
            (LocalDate(2026, 10, 1) to LocalDate(2026, 10, 31))
    }

    @Test
    fun `a month has a bar per day`() {
        ReadingWindow.current(ReadingPeriod.MONTH, today).bars { 0L }.size shouldBe 31
    }

    @Test
    fun `a year has a bar per month`() {
        ReadingWindow.current(ReadingPeriod.YEAR, today).bars { 0L }.map { it.first.month.ordinal } shouldBe
            (0..11).toList()
    }

    @Test
    fun `a month bar sums every day in it`() {
        ReadingWindow.current(ReadingPeriod.YEAR, today).bars { 1L }[1].ms shouldBe 28L
    }

    @Test
    fun `a range of three years or more has a bar per year`() {
        ReadingWindow(ReadingPeriod.CUSTOM, LocalDate(2022, 6, 1), today).bars { 0L }.map { it.first } shouldBe
            listOf(
                LocalDate(2022, 6, 1),
                LocalDate(2023, 1, 1),
                LocalDate(2024, 1, 1),
                LocalDate(2025, 1, 1),
                LocalDate(2026, 1, 1),
            )
    }

    @Test
    fun `a range's partial months are clipped to the range`() {
        val bars = ReadingWindow(ReadingPeriod.CUSTOM, LocalDate(2026, 1, 20), LocalDate(2026, 4, 10)).bars { 1L }
        bars.map { it.ms } shouldBe listOf(12L, 28L, 31L, 10L)
    }

    @Test
    fun `stepping a custom range moves it by its own length`() {
        ReadingWindow(ReadingPeriod.CUSTOM, LocalDate(2026, 10, 1), LocalDate(2026, 10, 10))
            .shifted(forward = false, today).first shouldBe LocalDate(2026, 9, 21)
    }

    @Test
    fun `stepping back from January lands on the previous December`() {
        ReadingWindow.current(ReadingPeriod.MONTH, LocalDate(2026, 1, 5)).shifted(forward = false, today).last shouldBe
            LocalDate(2025, 12, 31)
    }

    @Test
    fun `stepping a week forward never runs past today`() {
        ReadingWindow.week(LocalDate(2026, 10, 15)).shifted(forward = true, today).last shouldBe today
    }
}
