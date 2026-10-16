package reikai.presentation.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import reikai.domain.readinglog.EntryReadingSession
import reikai.domain.recommendation.taste.TrackStatus
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Titles per publishing status code; both content types share the codes (`NovelStatusCode`). */
fun List<StatsTitle>.statusCounts(): Map<Long, Int> = groupingBy { it.status }.eachCount()

/** Scored titles per 1 to 10 bar. */
fun List<StatsTitle>.scoreCounts(): Map<Int, Int> =
    mapNotNull { it.meanScore?.let(::scoreBar) }.groupingBy { it }.eachCount()

/** The bar a mean score sits in: rounded, and kept on the chart, as Yōkai's score chart bins it. */
fun scoreBar(mean: Double): Int = mean.roundToInt().coerceIn(1, 10)

fun List<StatsTitle>.trackStatusCounts(): Map<TrackStatus, Int> =
    mapNotNull { it.trackStatus }.groupingBy { it }.eachCount()

/** Reading time per local day; a session that crosses midnight is split between the days it spans. */
fun readingTimeByDay(sessions: List<EntryReadingSession>, timeZone: TimeZone): Map<LocalDate, Long> {
    val byDay = mutableMapOf<LocalDate, Long>()
    sessions.forEach { session ->
        val end = session.endedAt
        var start = end - session.duration.milliseconds
        while (start < end) {
            val day = start.toLocalDateTime(timeZone).date
            val sliceEnd = minOf(day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone), end)
            byDay[day] = (byDay[day] ?: 0L) + (sliceEnd - start).inWholeMilliseconds
            start = sliceEnd
        }
    }
    return byDay
}

data class ReadingStreaks(val current: Int, val longest: Int)

/** Runs of consecutive reading days. Today not read yet keeps yesterday's run alive, since the day is not over. */
fun readingStreaks(daysRead: Set<LocalDate>, today: LocalDate): ReadingStreaks {
    val yesterday = today.minus(1, DateTimeUnit.DAY)
    var current = 0
    var day = if (today in daysRead) today else yesterday
    while (day in daysRead) {
        current++
        day = day.minus(1, DateTimeUnit.DAY)
    }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    daysRead.sorted().forEach {
        run = if (previous != null && previous!!.plus(1, DateTimeUnit.DAY) == it) run + 1 else 1
        longest = maxOf(longest, run)
        previous = it
    }
    return ReadingStreaks(current, longest)
}

/** When the series was started: a tracker's start date, else the reading log's first session, else unknown. */
val StatsTitle.startedAt: Instant? get() = trackerStartedAt ?: firstLoggedReadAt

/** Each entry's first logged session start, for [StatsTitle.firstLoggedReadAt]. */
fun firstLoggedRead(sessions: List<EntryReadingSession>): Map<Long, Instant> = sessions
    .groupBy { it.entryId }
    .mapValues { (_, entry) -> entry.minOf { it.endedAt - it.duration.milliseconds } }

fun List<StatsTitle>.startedPerYear(timeZone: TimeZone): Map<Int, Int> =
    mapNotNull { it.startedAt?.toLocalDateTime(timeZone)?.year }.groupingBy { it }.eachCount()

fun List<StatsTitle>.finishedPerYear(timeZone: TimeZone): Map<Int, Int> =
    mapNotNull { it.trackerFinishedAt?.toLocalDateTime(timeZone)?.year }.groupingBy { it }.eachCount()

enum class StatsDimension { STATUS, SCORE, LENGTH, TRACKER, SOURCE, CATEGORY, TAG, LANGUAGE, START_YEAR }

/** One details row's identity; a null value is the "none" row (unscored, untracked, unknown). */
sealed interface StatsGroupKey {
    data class Status(val code: Long) : StatsGroupKey
    data class Score(val bar: Int?) : StatsGroupKey
    data class Length(val chapters: LongRange) : StatsGroupKey
    data class Tracker(val trackerId: Long?) : StatsGroupKey
    data class Source(val key: String, val name: String) : StatsGroupKey
    data class Category(val id: Long) : StatsGroupKey
    data class Tag(val name: String) : StatsGroupKey
    data class Language(val code: String) : StatsGroupKey
    data class StartYear(val year: Int?) : StatsGroupKey
}

data class StatsGroup(
    val key: StatsGroupKey,
    val count: Int,
    val readChapters: Long,
    val totalChapters: Long,
    val meanScore: Double?,
    val readDuration: Long,
)

/** Chapter-count bands, as Yōkai's details screen draws them. */
val STATS_LENGTHS = listOf(0L..0L, 1L..1L, 2L..10L, 11L..25L, 26L..50L, 51L..100L, 101L..200L, 201L..Long.MAX_VALUE)

/**
 * The details rows for [dimension]. A title sits in one row per value it has, so a series read from two
 * sources, or carrying three tags, counts under each; the rows of those dimensions can sum past the total.
 */
fun List<StatsTitle>.groupsBy(dimension: StatsDimension, timeZone: TimeZone): List<StatsGroup> {
    // Tags differ in case from source to source; library search matches them ignoring it, so rows do too.
    val tagSpelling = flatMap { it.genres }.map { it.trim() }.reversed().associateBy { it.lowercase() }
    return flatMap { title ->
        title.groupKeys(dimension, timeZone)
            .map { key ->
                if (key is StatsGroupKey.Tag) StatsGroupKey.Tag(tagSpelling.getValue(key.name.lowercase())) else key
            }
            .distinct()
            .map { it to title }
    }
        .groupBy({ it.first }, { it.second })
        .map { (key, titles) ->
            StatsGroup(
                key = key,
                count = titles.size,
                readChapters = titles.sumOf { it.readChapters },
                totalChapters = titles.sumOf { it.totalChapters },
                meanScore = titles.mapNotNull { it.meanScore }.takeIf { it.isNotEmpty() }?.average(),
                readDuration = titles.sumOf { it.readDuration },
            )
        }
}

private fun StatsTitle.groupKeys(dimension: StatsDimension, timeZone: TimeZone): List<StatsGroupKey> =
    when (dimension) {
        StatsDimension.STATUS -> listOf(StatsGroupKey.Status(status))
        StatsDimension.SCORE -> listOf(StatsGroupKey.Score(meanScore?.let(::scoreBar)))
        StatsDimension.LENGTH -> listOf(StatsGroupKey.Length(STATS_LENGTHS.first { totalChapters in it }))
        StatsDimension.TRACKER ->
            trackerIds.distinct().map { StatsGroupKey.Tracker(it) }.ifEmpty { listOf(StatsGroupKey.Tracker(null)) }
        StatsDimension.SOURCE -> sources.map { StatsGroupKey.Source(it.key, it.name) }
        // An entry filed nowhere sits in the Default category, id 0.
        StatsDimension.CATEGORY -> categoryIds.ifEmpty { listOf(0L) }.distinct().map { StatsGroupKey.Category(it) }
        StatsDimension.TAG -> genres.map { StatsGroupKey.Tag(it.trim()) }
        StatsDimension.LANGUAGE -> sources.map { StatsGroupKey.Language(it.language) }.distinct()
        StatsDimension.START_YEAR -> listOf(StatsGroupKey.StartYear(startedAt?.toLocalDateTime(timeZone)?.year))
    }
