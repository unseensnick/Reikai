package reikai.presentation.stats

import androidx.compose.runtime.Immutable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import reikai.domain.recommendation.taste.TrackStatus

/** What the overview's charts draw for the selected content type. */
@Immutable
data class StatsCharts(
    val status: Map<Long, Int>,
    val scores: Map<Int, Int>,
    val trackStatus: Map<TrackStatus, Int>,
    /** The last seven days' reading time, oldest first and today last. */
    val lastWeek: List<Pair<LocalDate, Long>>,
    val streaks: ReadingStreaks,
    /** The first day the reading log saw reading; null while it has seen none. */
    val loggedSince: LocalDate?,
    /** Each overview grouping's first rows, as its card shows them. */
    val groups: Map<StatsDimension, List<StatsRow>>,
    /** The series read longest, most first; one never read is left out. */
    val mostRead: List<StatsTitle>,
)

/** The groupings the overview shows a card of rows for; status and score have charts of their own. */
val OVERVIEW_GROUPINGS = listOf(
    StatsDimension.SOURCE,
    StatsDimension.TAG,
    StatsDimension.CATEGORY,
    StatsDimension.TRACKER,
    StatsDimension.LANGUAGE,
    StatsDimension.LENGTH,
    StatsDimension.START_YEAR,
)

/** How many rows an overview card shows before its See all. */
const val OVERVIEW_ROWS = 5

fun statsCharts(
    titles: List<StatsTitle>,
    sessions: List<TitleReadingSession>,
    groups: Map<StatsDimension, List<StatsRow>>,
    today: LocalDate,
    timeZone: TimeZone,
): StatsCharts {
    val byDay = readingTimeByDay(sessions.map { it.session }, timeZone)
    return StatsCharts(
        status = titles.statusCounts(),
        scores = titles.scoreCounts(),
        trackStatus = titles.trackStatusCounts(),
        lastWeek = weekEnding(today) { byDay[it] ?: 0L },
        streaks = readingStreaks(byDay.filterValues { it > 0L }.keys, today),
        loggedSince = sessions.minOfOrNull { it.session.startedAt }
            ?.toLocalDateTime(timeZone)?.date,
        groups = groups.mapValues { (_, rows) -> rows.take(OVERVIEW_ROWS) },
        mostRead = titles.mostRead().take(OVERVIEW_ROWS),
    )
}
