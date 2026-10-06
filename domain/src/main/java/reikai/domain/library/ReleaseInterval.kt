package reikai.domain.library

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Smart update's release prediction over plain dates, so manga (`FetchInterval`, upstream's) and novels
 * compute it once. An interval is in days; a negative one was set by the user and is kept as it is.
 */
object ReleaseInterval {

    const val MAX_INTERVAL = 28

    private const val GRACE_PERIOD = 1L

    /** The days around [date] an entry's next update has to fall in to be fetched now. */
    fun window(date: LocalDate, zone: TimeZone): Pair<Long, Long> {
        val today = date.atStartOfDayIn(zone)
        return Pair(
            (today - GRACE_PERIOD.days).toEpochMilliseconds(),
            (today + GRACE_PERIOD.days).toEpochMilliseconds(),
        )
    }

    /** [window], or [today]'s when it is (0, 0), which callers pass to mean "now". */
    fun windowOrToday(window: Pair<Long, Long>, today: LocalDate, zone: TimeZone): Pair<Long, Long> =
        if (window.first == 0L && window.second == 0L) window(today, zone) else window

    /**
     * Whether a sync that left the chapter list unchanged still predicts again: when asked for by hand,
     * when the entry was never predicted, or when its prediction has fallen behind [window]. A changed
     * list always predicts.
     */
    fun needsPrediction(
        manualFetch: Boolean,
        fetchInterval: Int,
        nextUpdate: Long,
        window: Pair<Long, Long>,
    ): Boolean = manualFetch || fetchInterval == 0 || nextUpdate < window.first

    /** The interval the user set, which is negative and kept as it is, else the one [predict] calculates. */
    inline fun userOrPredicted(fetchInterval: Int, predict: () -> Int): Int =
        fetchInterval.takeIf { it < 0 } ?: predict()

    /**
     * The median gap between the latest release days, one entry of [uploadDates] and [fetchDates] per
     * chapter. Upload dates are used when three or more are known, fetch dates otherwise, else a week.
     */
    fun calculate(uploadDates: List<Long>, fetchDates: List<Long>, zone: TimeZone): Int {
        val chapterWindow = if (uploadDates.size <= 8) 3 else 10
        val uploadDays = latestDays(uploadDates.filter { it > 0L }, zone, chapterWindow)
        val fetchDays = latestDays(fetchDates, zone, chapterWindow)

        val interval = when {
            uploadDays.size >= 3 -> medianGap(uploadDays, zone)
            fetchDays.size >= 3 -> medianGap(fetchDays, zone)
            else -> 7
        }
        return interval.coerceIn(1, MAX_INTERVAL)
    }

    /** When an entry last changed on [lastUpdate] is next due, keeping a [nextUpdate] already inside [window]. */
    fun nextUpdate(
        nextUpdate: Long,
        lastUpdate: Long,
        interval: Int,
        dateTime: LocalDateTime,
        zone: TimeZone,
        window: Pair<Long, Long>,
    ): Long {
        if (nextUpdate in window.first.rangeTo(window.second + 1)) return nextUpdate

        val instant = if (lastUpdate > 0) Instant.fromEpochMilliseconds(lastUpdate) else kotlin.time.Clock.System.now()
        val latestDate = instant.toLocalDateTime(zone).date.atStartOfDayIn(zone)

        val daysSinceLatest = (dateTime.toInstant(zone) - latestDate).inWholeDays
        val cycle = daysSinceLatest.floorDiv(
            interval.absoluteValue.takeIf { interval < 0 }
                ?: increaseInterval(interval, daysSinceLatest, increaseWhenOver = 10),
        )

        val offsetDays = ((cycle + 1) * interval.absoluteValue.toLong()).days
        return latestDate.plus(offsetDays).toEpochMilliseconds()
    }

    private fun latestDays(dates: List<Long>, zone: TimeZone, count: Int) = dates.asSequence()
        .sortedDescending()
        .map { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date.atStartOfDayIn(zone) }
        .distinct()
        .take(count)
        .toList()

    private fun medianGap(days: List<Instant>, zone: TimeZone): Int {
        val ranges = days.windowed(2).map { x -> x[1].daysUntil(x[0], zone) }.sorted()
        return ranges[(ranges.size - 1) / 2]
    }

    /** Doubles the interval while more than [increaseWhenOver] checks at it have been missed. */
    private fun increaseInterval(delta: Int, daysSinceLatest: Long, increaseWhenOver: Int): Int {
        if (delta >= MAX_INTERVAL) return MAX_INTERVAL
        val cycle = daysSinceLatest.floorDiv(delta) + 1
        return if (cycle > increaseWhenOver) increaseInterval(delta * 2, daysSinceLatest, increaseWhenOver) else delta
    }
}
