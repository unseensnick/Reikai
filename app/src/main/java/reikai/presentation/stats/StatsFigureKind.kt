package reikai.presentation.stats

import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.rounded.Verified
import reikai.util.inOrderOf
import tachiyomi.i18n.MR

/**
 * A figure on the At a glance card, by the [key] its place and its hiding are stored under, in the default order. [label]
 * sits under the tile's number; [title] names it in the arrange list, where "Used" or "Total" alone mean nothing.
 */
enum class StatsFigureKind(
    val key: String,
    val icon: ImageVector,
    val label: StringResource,
    val title: StringResource = label,
) {
    IN_LIBRARY("in_library", MaterialSymbols.Rounded.CollectionsBookmark, MR.strings.in_library),
    READ_DURATION("read_duration", MaterialSymbols.Rounded.Schedule, MR.strings.label_read_duration),
    COMPLETED("completed", MaterialSymbols.Rounded.LocalLibrary, MR.strings.label_completed_titles),
    GLOBAL_UPDATE("global_update", MaterialSymbols.Rounded.Sync, MR.strings.label_titles_in_global_update),
    STARTED("started", MaterialSymbols.Rounded.Bookmark, MR.strings.label_started),
    LOCAL("local", MaterialSymbols.Rounded.Folder, MR.strings.label_local),
    TOTAL_CHAPTERS(
        "total_chapters",
        MaterialSymbols.Rounded.Book,
        MR.strings.label_total_chapters,
        MR.strings.action_sort_total,
    ),
    READ_CHAPTERS(
        "read_chapters",
        MaterialSymbols.Rounded.DoneAll,
        MR.strings.label_read_chapters,
        MR.strings.stats_figure_read_chapters,
    ),
    DOWNLOADED("downloaded", MaterialSymbols.Rounded.Download, MR.strings.label_downloaded),
    TRACKED_TITLES("tracked_titles", MaterialSymbols.Rounded.Verified, MR.strings.label_tracked_titles),
    MEAN_SCORE("mean_score", MaterialSymbols.Rounded.Favorite, MR.strings.label_mean_score),
    TRACKERS_USED(
        "trackers_used",
        MaterialSymbols.Rounded.Public,
        MR.strings.label_used,
        MR.strings.stats_figure_trackers_used,
    ),
}

/** Every figure in the saved [order], then any the order does not name, in their default order. */
fun statsFigureLayout(order: List<String>): List<StatsFigureKind> = StatsFigureKind.entries.inOrderOf(order) { it.key }

/** The figures [keys] name; a key that names no figure is ignored. */
fun statsFiguresByKey(keys: Collection<String>): Set<StatsFigureKind> =
    StatsFigureKind.entries.filterTo(mutableSetOf()) { it.key in keys }

/** The cards the overview draws: none of [hiddenCards], and no At a glance card with every figure hidden. */
fun statsShownCards(
    layout: List<StatsCardKind>,
    hiddenCards: Set<StatsCardKind>,
    hiddenFigures: Set<StatsFigureKind>,
): List<StatsCardKind> = layout.filter {
    it !in hiddenCards && !(it == StatsCardKind.OVERVIEW && hiddenFigures.containsAll(StatsFigureKind.entries))
}
