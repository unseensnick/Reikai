package reikai.presentation.library

import reikai.domain.category.matchesCategoryFilter
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.applyFilter

/**
 * The resolved per-session filter, shared by the manga and novel libraries. Each axis is a [TriState]
 * (DISABLED = ignore, ENABLED_IS = keep matches, ENABLED_NOT = keep non-matches). The caller resolves
 * the prefs first: it folds the global Downloaded-only mode into [downloaded], and sets [intervalCustom]
 * to DISABLED when the release-period gate is off, so the shared predicate stays a plain [applyFilter].
 * Tracking and category filtering use include/exclude id sets rather than a tri-state.
 */
data class LibraryFilterPrefs(
    val downloaded: TriState,
    val unread: TriState,
    val started: TriState,
    val bookmarked: TriState,
    val completed: TriState,
    val intervalCustom: TriState,
    val lewd: TriState,
    val includedTracks: Set<Long>,
    val excludedTracks: Set<Long>,
    val categoriesActive: Boolean,
    val categoriesInclude: Set<Long>,
    val categoriesExclude: Set<Long>,
)

/**
 * Per-entry accessors [libraryFilterMatches] reads, so the filter never depends on the concrete row type
 * (the manga `LibraryItem` vs the novel `CollapsedNovel`). Each library supplies getters over its own row.
 * The per-type seams live here: [isDownloaded] folds in manga's local-source concept (novels have none),
 * [isLewd] folds in manga's source-name check (novels are genre-only), [matchesIntervalCustom] is always
 * false for novels (they have no fetch interval, and the axis is DISABLED for them anyway), and
 * [trackerIds] is each side's merge-group union.
 */
class LibraryFilterFields<T>(
    val isDownloaded: (T) -> Boolean,
    val isUnread: (T) -> Boolean,
    val hasStarted: (T) -> Boolean,
    val hasBookmarks: (T) -> Boolean,
    val isCompleted: (T) -> Boolean,
    val matchesIntervalCustom: (T) -> Boolean,
    val isLewd: (T) -> Boolean,
    val trackerIds: (T) -> List<Long>,
    val categoryIds: (T) -> Collection<Long>,
)

/** Whether [row] passes every active axis of [prefs]. Pure over the [fields] accessors. */
fun <T> libraryFilterMatches(
    row: T,
    prefs: LibraryFilterPrefs,
    fields: LibraryFilterFields<T>,
): Boolean =
    applyFilter(prefs.downloaded) { fields.isDownloaded(row) } &&
        applyFilter(prefs.unread) { fields.isUnread(row) } &&
        applyFilter(prefs.started) { fields.hasStarted(row) } &&
        applyFilter(prefs.bookmarked) { fields.hasBookmarks(row) } &&
        applyFilter(prefs.completed) { fields.isCompleted(row) } &&
        applyFilter(prefs.intervalCustom) { fields.matchesIntervalCustom(row) } &&
        applyFilter(prefs.lewd) { fields.isLewd(row) } &&
        matchesTrackingFilter(fields.trackerIds(row), prefs.includedTracks, prefs.excludedTracks) &&
        (
            !prefs.categoriesActive ||
                matchesCategoryFilter(fields.categoryIds(row), prefs.categoriesInclude, prefs.categoriesExclude)
            )

/**
 * The include/exclude tri-state over a group's unioned tracker ids, shared by both libraries. An entry
 * passes when it carries no excluded tracker and at least one included tracker (or none are required).
 * With neither set, tracking is not filtered.
 */
private fun matchesTrackingFilter(trackerIds: List<Long>, included: Set<Long>, excluded: Set<Long>): Boolean {
    if (included.isEmpty() && excluded.isEmpty()) return true
    val isExcluded = excluded.isNotEmpty() && trackerIds.any { it in excluded }
    val isIncluded = included.isEmpty() || trackerIds.any { it in included }
    return !isExcluded && isIncluded
}
