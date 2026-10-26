package reikai.presentation.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import reikai.domain.category.orUncategorized
import reikai.domain.entry.EntryId
import reikai.domain.readinglog.EntryReadingSession
import reikai.domain.recommendation.taste.TrackStatus
import java.io.Serializable
import kotlin.math.roundToInt
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

/** Reading time per local day. */
fun readingTimeByDay(sessions: List<EntryReadingSession>, timeZone: TimeZone): Map<LocalDate, Long> {
    val byDay = mutableMapOf<LocalDate, Long>()
    sessions.forEach { session ->
        session.splitByDay(timeZone) { day, ms -> byDay[day] = (byDay[day] ?: 0L) + ms }
    }
    return byDay
}

/** Reading time per local day and series; keyed by [EntryId], since a manga and a novel can share a row id. */
fun readingTimeByDayAndTitle(
    sessions: List<TitleReadingSession>,
    timeZone: TimeZone,
): Map<LocalDate, Map<EntryId, Long>> {
    val byDay = mutableMapOf<LocalDate, MutableMap<EntryId, Long>>()
    sessions.forEach { (entryId, _, session) ->
        session.splitByDay(timeZone) { day, ms ->
            val byTitle = byDay.getOrPut(day) { mutableMapOf() }
            byTitle[entryId] = (byTitle[entryId] ?: 0L) + ms
        }
    }
    return byDay
}

/** Hands each local day this session spans its share; a session that crosses midnight is split. */
private inline fun EntryReadingSession.splitByDay(timeZone: TimeZone, add: (LocalDate, Long) -> Unit) {
    var start = startedAt
    while (start < endedAt) {
        val day = start.toLocalDateTime(timeZone).date
        val sliceEnd = minOf(day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone), endedAt)
        add(day, (sliceEnd - start).inWholeMilliseconds)
        start = sliceEnd
    }
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
        run = if (previous?.plus(1, DateTimeUnit.DAY) == it) run + 1 else 1
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
    .mapValues { (_, entry) -> entry.minOf { it.startedAt } }

enum class StatsDimension { STATUS, SCORE, LENGTH, TRACKER, SOURCE, CATEGORY, TAG, LANGUAGE, START_YEAR, TRACK_STATUS }

/**
 * One details row's identity; a null value is the "none" row (unscored, untracked, unknown). Serializable
 * because the row's series list is a screen that takes one.
 */
sealed interface StatsGroupKey : Serializable {
    data class Status(val code: Long) : StatsGroupKey
    data class Score(val bar: Int?) : StatsGroupKey

    // Two ends rather than a LongRange, which cannot be saved with a screen.
    data class Length(val first: Long, val last: Long) : StatsGroupKey {
        val chapters: LongRange get() = first..last
    }
    data class Tracker(val trackerId: Long?) : StatsGroupKey
    data class Source(val key: String, val name: String) : StatsGroupKey
    data class Category(val id: Long) : StatsGroupKey
    data class Tag(val name: String) : StatsGroupKey
    data class Language(val code: String) : StatsGroupKey
    data class StartYear(val year: Int?) : StatsGroupKey
    data class TrackingStatus(val status: TrackStatus) : StatsGroupKey
}

val StatsGroupKey.dimension: StatsDimension
    get() = when (this) {
        is StatsGroupKey.Status -> StatsDimension.STATUS
        is StatsGroupKey.Score -> StatsDimension.SCORE
        is StatsGroupKey.Length -> StatsDimension.LENGTH
        is StatsGroupKey.Tracker -> StatsDimension.TRACKER
        is StatsGroupKey.Source -> StatsDimension.SOURCE
        is StatsGroupKey.Category -> StatsDimension.CATEGORY
        is StatsGroupKey.Tag -> StatsDimension.TAG
        is StatsGroupKey.Language -> StatsDimension.LANGUAGE
        is StatsGroupKey.StartYear -> StatsDimension.START_YEAR
        is StatsGroupKey.TrackingStatus -> StatsDimension.TRACK_STATUS
    }

data class StatsGroup(
    val key: StatsGroupKey,
    val count: Int,
    val readChapters: Long,
    val totalChapters: Long,
    val meanScore: Double?,
    val readDuration: Long,
    /** The row's series by how far they are read; the three always add up to [count]. */
    val finished: Int,
    val reading: Int,
    val notStarted: Int,
)

/** How far a series is read: every chapter, some, or none. A series with no chapters has not started. */
enum class ReadState { FINISHED, READING, NOT_STARTED }

val StatsTitle.readState: ReadState
    get() = when {
        readChapters <= 0L -> ReadState.NOT_STARTED
        readChapters >= totalChapters -> ReadState.FINISHED
        else -> ReadState.READING
    }

/** Chapter-count bands, as Yōkai's details screen draws them. */
val STATS_LENGTHS = listOf(0L..0L, 1L..1L, 2L..10L, 11L..25L, 26L..50L, 51L..100L, 101L..200L, 201L..Long.MAX_VALUE)

/**
 * The details rows for [dimension]. A title sits in one row per value it has, so a series read from two
 * sources, or carrying three tags, counts under each; the rows of those dimensions can sum past the total.
 */
fun List<StatsTitle>.groupsBy(dimension: StatsDimension, timeZone: TimeZone): List<StatsGroup> =
    titlesByGroup(dimension, timeZone)
        .map { (key, titles) ->
            StatsGroup(
                key = key,
                count = titles.size,
                readChapters = titles.sumOf { it.readChapters },
                totalChapters = titles.sumOf { it.totalChapters },
                meanScore = titles.mapNotNull { it.meanScore }.takeIf { it.isNotEmpty() }?.average(),
                readDuration = titles.sumOf { it.readDuration },
                finished = titles.count { it.readState == ReadState.FINISHED },
                reading = titles.count { it.readState == ReadState.READING },
                notStarted = titles.count { it.readState == ReadState.NOT_STARTED },
            )
        }

/** The titles a [groupsBy] row of [key] counts, so a row's list can never disagree with its number. */
fun List<StatsTitle>.titlesIn(key: StatsGroupKey, timeZone: TimeZone): List<StatsTitle> =
    titlesByGroup(key.dimension, timeZone)[key].orEmpty()

private fun List<StatsTitle>.titlesByGroup(
    dimension: StatsDimension,
    timeZone: TimeZone,
): Map<StatsGroupKey, List<StatsTitle>> {
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
}

private fun StatsTitle.groupKeys(dimension: StatsDimension, timeZone: TimeZone): List<StatsGroupKey> =
    when (dimension) {
        StatsDimension.STATUS -> listOf(StatsGroupKey.Status(status))
        StatsDimension.SCORE -> listOf(StatsGroupKey.Score(meanScore?.let(::scoreBar)))
        StatsDimension.LENGTH ->
            STATS_LENGTHS.first { totalChapters in it }.let { listOf(StatsGroupKey.Length(it.first, it.last)) }
        StatsDimension.TRACKER ->
            trackerIds.distinct().map { StatsGroupKey.Tracker(it) }.ifEmpty { listOf(StatsGroupKey.Tracker(null)) }
        StatsDimension.SOURCE -> sources.map { StatsGroupKey.Source(it.key, it.name) }
        // An entry filed nowhere sits in the Default category, id 0.
        StatsDimension.CATEGORY -> categoryIds.orUncategorized().distinct().map { StatsGroupKey.Category(it) }
        StatsDimension.TAG -> genres.map { StatsGroupKey.Tag(it.trim()) }
        StatsDimension.LANGUAGE -> sources.map { StatsGroupKey.Language(it.language) }.distinct()
        StatsDimension.START_YEAR -> listOf(StatsGroupKey.StartYear(startedAt?.toLocalDateTime(timeZone)?.year))
        // An untracked series has no tracking status, so it sits in no row, as the chart leaves it out.
        StatsDimension.TRACK_STATUS -> listOfNotNull(trackStatus?.let(StatsGroupKey::TrackingStatus))
    }

/** The seven days ending on [end], oldest first, each with its [total]: the week the reading screen opens on. */
fun weekEnding(end: LocalDate, total: (LocalDate) -> Long): List<Pair<LocalDate, Long>> =
    ReadingWindow.week(end).days().map { it to total(it) }
