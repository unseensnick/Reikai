package reikai.presentation.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.plus

/** The span the reading-time screen covers; a custom one is any range the user picks. */
enum class ReadingPeriod { WEEK, MONTH, YEAR, CUSTOM }

/** What one bar of a window stands for. */
enum class BarUnit { DAY, MONTH, YEAR }

/** One bar: its dates, clipped to the window, and the reading time across them. */
data class ReadingBar(val first: LocalDate, val last: LocalDate, val ms: Long)

/** Dates [first] to [last], both included. */
data class ReadingWindow(val period: ReadingPeriod, val first: LocalDate, val last: LocalDate) {

    /** A bar per day up to two months, per month up to three years, per year past that, so bars stay tappable. */
    val unit: BarUnit
        get() = when {
            first.daysUntil(last) < 62 -> BarUnit.DAY
            startOfMonth(first).monthsUntil(startOfMonth(last)) < 36 -> BarUnit.MONTH
            else -> BarUnit.YEAR
        }

    fun days(): List<LocalDate> = datesBetween(first, last)

    fun bars(total: (LocalDate) -> Long): List<ReadingBar> {
        val starts = generateSequence(first) { start ->
            when (unit) {
                BarUnit.DAY -> start.plus(1, DateTimeUnit.DAY)
                BarUnit.MONTH -> startOfMonth(start).plus(1, DateTimeUnit.MONTH)
                BarUnit.YEAR -> LocalDate(start.year + 1, Month.JANUARY, 1)
            }
        }.takeWhile { it <= last }.toList()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.minus(1, DateTimeUnit.DAY) ?: last
            ReadingBar(start, end, datesBetween(start, end).sumOf(total))
        }
    }

    /** The window before, or after ([forward]), this one; a week never runs past [today]. */
    fun shifted(forward: Boolean, today: LocalDate): ReadingWindow {
        val step = if (forward) 1 else -1
        return when (period) {
            ReadingPeriod.WEEK -> week(minOf(last.plus(7 * step, DateTimeUnit.DAY), today))
            ReadingPeriod.MONTH -> month(first.plus(step, DateTimeUnit.MONTH))
            ReadingPeriod.YEAR -> year(first.year + step)
            ReadingPeriod.CUSTOM -> {
                val length = first.daysUntil(last) + 1
                copy(
                    first = first.plus(length * step, DateTimeUnit.DAY),
                    last = last.plus(length * step, DateTimeUnit.DAY),
                )
            }
        }
    }

    companion object {
        /**
         * The [period] holding [today]: the seven days ending on it, its calendar month or its calendar year. A
         * custom range has no current one, so it starts from the week until a range is picked.
         */
        fun current(period: ReadingPeriod, today: LocalDate): ReadingWindow = when (period) {
            ReadingPeriod.WEEK, ReadingPeriod.CUSTOM -> week(today)
            ReadingPeriod.MONTH -> month(today)
            ReadingPeriod.YEAR -> year(today.year)
        }

        fun week(end: LocalDate) = ReadingWindow(ReadingPeriod.WEEK, end.minus(6, DateTimeUnit.DAY), end)

        private fun month(day: LocalDate): ReadingWindow {
            val first = startOfMonth(day)
            return ReadingWindow(
                ReadingPeriod.MONTH,
                first,
                first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY),
            )
        }

        private fun year(year: Int) =
            ReadingWindow(ReadingPeriod.YEAR, LocalDate(year, Month.JANUARY, 1), LocalDate(year, Month.DECEMBER, 31))
    }
}

private fun startOfMonth(day: LocalDate) = LocalDate(day.year, day.month, 1)

private fun datesBetween(first: LocalDate, last: LocalDate): List<LocalDate> =
    generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= last }.toList()
