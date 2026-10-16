package reikai.presentation.stats

import androidx.compose.runtime.Immutable
import kotlinx.datetime.TimeZone
import reikai.domain.library.ContentType

enum class StatsSort { COUNT, READ, SCORE, TIME }

/** A grouping's row: [name] is set where the key names an id the screen cannot resolve itself. */
@Immutable
data class StatsRow(val group: StatsGroup, val name: String?, val query: String?)

/** Groupings with no order of their own, which [StatsSort] reorders; the rest keep their own order. */
val StatsDimension.isSortable: Boolean
    get() = this !in setOf(StatsDimension.SCORE, StatsDimension.LENGTH, StatsDimension.START_YEAR)

/** [dimension]'s rows for [type], named, ordered and each with the library search that lists it. */
fun StatsLibrary.rows(
    dimension: StatsDimension,
    type: ContentType,
    sort: StatsSort,
    timeZone: TimeZone,
): List<StatsRow> =
    titles(type).groupsBy(dimension, timeZone).ordered(dimension, sort).map { group ->
        StatsRow(
            group = group,
            name = nameOf(group.key),
            query = group.key.libraryQuery(trackerNames::get, categoryNames::get),
        )
    }

private fun StatsLibrary.nameOf(key: StatsGroupKey): String? = when (key) {
    is StatsGroupKey.Tracker -> key.trackerId?.let(trackerNames::get)
    // The Default category's stored name is empty; the screen names it.
    is StatsGroupKey.Category -> categoryNames[key.id]?.takeIf { it.isNotEmpty() }
    is StatsGroupKey.Source -> key.name
    is StatsGroupKey.Tag -> key.name
    else -> null
}

private fun List<StatsGroup>.ordered(dimension: StatsDimension, sort: StatsSort): List<StatsGroup> = when (dimension) {
    StatsDimension.SCORE -> sortedWith(compareByDescending(nullsFirst()) { (it.key as StatsGroupKey.Score).bar })
    StatsDimension.LENGTH -> sortedBy { (it.key as StatsGroupKey.Length).chapters.first }
    StatsDimension.START_YEAR -> sortedWith(
        compareByDescending(nullsFirst()) {
            (it.key as StatsGroupKey.StartYear).year
        },
    )
    else -> when (sort) {
        StatsSort.COUNT -> sortedByDescending { it.count }
        StatsSort.READ -> sortedByDescending { it.readChapters }
        StatsSort.SCORE -> sortedByDescending { it.meanScore ?: -1.0 }
        StatsSort.TIME -> sortedByDescending { it.readDuration }
    }
}
