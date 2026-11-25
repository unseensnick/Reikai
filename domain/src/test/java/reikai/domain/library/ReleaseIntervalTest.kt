package reikai.domain.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The release prediction manga's `FetchInterval` and the novel prediction both call. */
class ReleaseIntervalTest {

    private val zone = TimeZone.UTC
    private val window = at(1_000L)..at(2_000L)

    @Test
    fun `an unchanged list with a prediction inside the window is not predicted again`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, at(1_500L), window) shouldBe false
    }

    @Test
    fun `a refresh asked for by hand predicts again`() {
        ReleaseInterval.needsPrediction(manualFetch = true, fetchInterval = 3, nextUpdate = at(1_500L), window) shouldBe
            true
    }

    @Test
    fun `an entry never predicted is predicted`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 0, at(1_500L), window) shouldBe true
    }

    @Test
    fun `a prediction fallen behind the window is predicted again`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, nextUpdate = at(999L), window) shouldBe
            true
    }

    @Test
    fun `a missing prediction is predicted when there is a window`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, nextUpdate = null, window) shouldBe
            true
    }

    @Test
    fun `without a window only a hand refresh or a new entry predicts`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, null, window = null) shouldBe false
    }

    @Test
    fun `an interval the user set is kept and reads no chapters`() = runTest {
        predict(fetchInterval = -5) { error("an interval the user set reads no chapters") }.interval shouldBe -5
    }

    @Test
    fun `an interval nobody set is predicted`() = runTest {
        predict { datesOn(everyDays(from = "2019-12-01", to = "2019-12-31", days = 2)) }.interval shouldBe 2
    }

    @Test
    fun `releases spread over most of the week keep a plain interval`() {
        val days = listOf("01", "03", "04", "09", "11", "13", "14", "19", "21", "24").map { "2020-01-$it" }

        schedule(days.map(LocalDate::parse), today = "2020-01-25").pattern shouldBe
            ReleasePattern.EveryDays(2, onSchedule = LocalDate.parse("2020-01-21"))
    }

    @Test
    fun `too few releases keep a plain interval`() {
        val days = listOf("2020-01-06", "2020-01-09", "2020-01-13")

        schedule(days.map(LocalDate::parse), today = "2020-01-14").pattern shouldBe
            ReleasePattern.EveryDays(3, onSchedule = LocalDate.parse("2020-01-09"))
    }

    @Test
    fun `a day of the month moves to the end of a shorter month`() {
        ReleasePattern.DaysOfMonth(setOf(31)).nextAfter(LocalDate.parse("2020-01-31")) shouldBe
            LocalDate.parse("2020-02-29")
    }

    @Test
    fun `an interval the user set counts whole cycles from the last update`() = runTest {
        val lastUpdate = LocalDate.parse("2019-12-20").atStartOfDayIn(zone) + 15.hours

        predict(fetchInterval = -5, lastUpdate = lastUpdate) { error("unread") }
            .nextUpdate.toLocalDateTime(zone).date shouldBe LocalDate.parse("2020-01-04")
    }

    @Test
    fun `no window means today's`() {
        val today = LocalDate(2026, 9, 17)

        ReleaseInterval.windowOrToday(null, today, zone) shouldBe ReleaseInterval.window(today, zone)
    }

    @Test
    fun `a given window is kept`() {
        ReleaseInterval.windowOrToday(window, LocalDate(2026, 9, 17), zone) shouldBe window
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)

    private suspend fun predict(
        fetchInterval: Int = 0,
        nextUpdate: Instant? = null,
        lastUpdate: Instant? = null,
        today: LocalDate = LocalDate.parse("2020-01-01"),
        dates: suspend () -> ChapterDates,
    ) = ReleaseInterval.predict(
        fetchInterval = fetchInterval,
        nextUpdate = nextUpdate,
        lastUpdate = lastUpdate,
        dateTime = today.atTime(10, 0),
        zone = zone,
        window = ReleaseInterval.window(today, zone),
        chapterDates = dates,
    )

    private fun schedule(days: List<LocalDate>, today: String) =
        ReleaseSchedule.estimate(datesOn(days), zone, LocalDate.parse(today))

    private fun everyDays(from: String, to: String, days: Int) =
        generateSequence(LocalDate.parse(from)) { it.plus(DatePeriod(days = days)) }
            .takeWhile { it <= LocalDate.parse(to) }
            .toList()

    private fun datesOn(days: List<LocalDate>, perDay: Int = 1): ChapterDates {
        val times = days.flatMap { day -> List(perDay) { day.atStartOfDayIn(zone) + 12.hours } }
        return ChapterDates(upload = times, fetch = times)
    }
}
