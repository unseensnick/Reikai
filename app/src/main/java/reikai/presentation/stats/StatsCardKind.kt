package reikai.presentation.stats

import dev.icerock.moko.resources.StringResource
import reikai.util.inSavedOrder
import tachiyomi.i18n.MR

/**
 * A card on the Statistics overview, by the [key] its place in a saved order is stored under. Declaration
 * order is the default layout. A card never leaves its [shelf], since the shelf decides how many fit across.
 */
enum class StatsCardKind(
    val key: String,
    val shelf: StatsShelf,
    val title: StringResource,
    val grouping: StatsDimension? = null,
) {
    OVERVIEW("overview", StatsShelf.GLANCE, MR.strings.label_overview_section),
    PUBLISHING("publishing", StatsShelf.CHARTS, MR.strings.stats_publishing_status),

    // Second, so on a wide screen the two tall cards share a row and the two short ones the next
    READING("reading", StatsShelf.CHARTS, MR.strings.stats_reading_week),
    TRACKING("tracking", StatsShelf.CHARTS, MR.strings.stats_tracking_status),
    SCORES("scores", StatsShelf.CHARTS, MR.strings.stats_scores),
    MOST_READ("most_read", StatsShelf.LISTS, MR.strings.stats_most_read),
    SOURCE("source", StatsShelf.LISTS, StatsDimension.SOURCE.title, StatsDimension.SOURCE),
    TAG("tag", StatsShelf.LISTS, StatsDimension.TAG.title, StatsDimension.TAG),
    CATEGORY("category", StatsShelf.LISTS, StatsDimension.CATEGORY.title, StatsDimension.CATEGORY),
    TRACKER("tracker", StatsShelf.LISTS, StatsDimension.TRACKER.title, StatsDimension.TRACKER),
    LANGUAGE("language", StatsShelf.LISTS, StatsDimension.LANGUAGE.title, StatsDimension.LANGUAGE),
    LENGTH("length", StatsShelf.LISTS, StatsDimension.LENGTH.title, StatsDimension.LENGTH),
    START_YEAR("start_year", StatsShelf.LISTS, StatsDimension.START_YEAR.title, StatsDimension.START_YEAR),
}

/** Every card in the saved [order], each shelf's cards together and the shelves in the order their first card comes. */
fun statsCardLayout(order: List<String>): List<StatsCardKind> =
    StatsCardKind.entries.inSavedOrder(order) { it.key }.groupBy { it.shelf }.values.flatten()

/** The cards [keys] name; a key that names no card is ignored. */
fun statsCardsByKey(keys: Collection<String>): Set<StatsCardKind> =
    StatsCardKind.entries.filterTo(mutableSetOf()) { it.key in keys }

/** This layout with its shelves in [shelves]' order, each keeping its cards' order. */
fun List<StatsCardKind>.withShelves(shelves: List<StatsShelf>): List<StatsCardKind> {
    val byShelf = groupBy { it.shelf }
    return shelves.flatMap { byShelf[it].orEmpty() }
}

/** This layout with [shelf]'s cards in the order of [cards], the other shelves left as they are. */
fun List<StatsCardKind>.withShelfCards(shelf: StatsShelf, cards: List<StatsCardKind>): List<StatsCardKind> =
    groupBy { it.shelf }.flatMap { (own, ownCards) -> if (own == shelf) cards else ownCards }
