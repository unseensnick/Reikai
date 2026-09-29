package reikai.presentation.library

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import reikai.domain.category.categoryFilterActive
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.effectiveIntervalFilter
import reikai.presentation.category.toLongIdSet
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.applyFilter

/**
 * The library-wide filter preferences as stored, read by both libraries through
 * [libraryFilterSettingsFlow]. Only [skipsOutsideReleasePeriod] differs per content type: it is the
 * library's own update restriction, which decides whether the custom-interval axis applies at all.
 * [trackers] holds one tri-state per logged-in tracker, so its keys are the logged-in tracker ids.
 */
data class LibraryFilterSettings(
    val downloadedOnly: Boolean,
    val downloaded: TriState,
    val unread: TriState,
    val started: TriState,
    val bookmarked: TriState,
    val completed: TriState,
    val intervalCustom: TriState,
    val skipsOutsideReleasePeriod: Boolean,
    val lewd: TriState,
    val trackers: Map<Long, TriState>,
    val categoriesEnabled: Boolean,
    val categoriesInclude: Set<Long>,
    val categoriesExclude: Set<Long>,
) {
    private val effectiveInterval get() = effectiveIntervalFilter(skipsOutsideReleasePeriod, intervalCustom)

    private val categoriesActive get() = categoryFilterActive(categoriesEnabled, categoriesInclude, categoriesExclude)

    /** Whether the filter icon lights. Downloaded-only mode is left out: it is a global mode, not a filter. */
    val isActive: Boolean
        get() = categoriesActive ||
            listOf(downloaded, unread, started, bookmarked, completed, effectiveInterval, lewd)
                .plus(trackers.values)
                .any { it != TriState.DISABLED }

    fun resolve(): LibraryFilterPrefs = LibraryFilterPrefs(
        downloaded = if (downloadedOnly) TriState.ENABLED_IS else downloaded,
        unread = unread,
        started = started,
        bookmarked = bookmarked,
        completed = completed,
        intervalCustom = effectiveInterval,
        lewd = lewd,
        includedTracks = trackers.filterValues { it == TriState.ENABLED_IS }.keys,
        excludedTracks = trackers.filterValues { it == TriState.ENABLED_NOT }.keys,
        categoriesActive = categoriesActive,
        categoriesInclude = categoriesInclude,
        categoriesExclude = categoriesExclude,
    )
}

/**
 * The stored filter preferences as one flow, for both libraries. [updateRestrictions] is the caller's
 * own update-restriction set, the one input that differs per content type.
 */
fun libraryFilterSettingsFlow(
    libraryPreferences: LibraryPreferences,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    basePreferences: BasePreferences,
    trackerManager: TrackerManager,
    updateRestrictions: Flow<Set<String>>,
): Flow<LibraryFilterSettings> = combine(
    combine(
        libraryPreferences.filterDownloaded.changes(),
        libraryPreferences.filterUnread.changes(),
        libraryPreferences.filterStarted.changes(),
        libraryPreferences.filterBookmarked.changes(),
        libraryPreferences.filterCompleted.changes(),
        ::ProgressAxes,
    ),
    combine(
        libraryPreferences.filterIntervalCustom.changes(),
        updateRestrictions.map { LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in it },
        ::Pair,
    ),
    combine(
        reikaiLibraryPreferences.filterCategories.changes(),
        reikaiLibraryPreferences.filterCategoriesInclude.changes(),
        reikaiLibraryPreferences.filterCategoriesExclude.changes(),
        ::Triple,
    ),
    combine(reikaiLibraryPreferences.filterLewd.changes(), basePreferences.downloadedOnly.changes(), ::Pair),
    trackerFiltersFlow(libraryPreferences, trackerManager),
) {
        progress,
        (interval, skipsOutsideReleasePeriod),
        (categoriesEnabled, include, exclude),
        (lewd, downloadedOnly),
        trackers,
    ->
    LibraryFilterSettings(
        downloadedOnly = downloadedOnly,
        downloaded = progress.downloaded,
        unread = progress.unread,
        started = progress.started,
        bookmarked = progress.bookmarked,
        completed = progress.completed,
        intervalCustom = interval,
        skipsOutsideReleasePeriod = skipsOutsideReleasePeriod,
        lewd = lewd,
        trackers = trackers,
        categoriesEnabled = categoriesEnabled,
        categoriesInclude = include.toLongIdSet(),
        categoriesExclude = exclude.toLongIdSet(),
    )
}

private class ProgressAxes(
    val downloaded: TriState,
    val unread: TriState,
    val started: TriState,
    val bookmarked: TriState,
    val completed: TriState,
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun trackerFiltersFlow(
    libraryPreferences: LibraryPreferences,
    trackerManager: TrackerManager,
): Flow<Map<Long, TriState>> = trackerManager.loggedInTrackersFlow().flatMapLatest { trackers ->
    if (trackers.isEmpty()) {
        flowOf(emptyMap())
    } else {
        combine(
            trackers.map { tracker ->
                libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
            },
        ) { it.toMap() }
    }
}

/**
 * The resolved per-session filter, shared by the manga and novel libraries. Each axis is a [TriState]
 * (DISABLED = ignore, ENABLED_IS = keep matches, ENABLED_NOT = keep non-matches). Built by
 * [LibraryFilterSettings.resolve], which folds the global Downloaded-only mode into [downloaded] and
 * switches [intervalCustom] off while the release-period gate is, so the predicate stays a plain
 * [applyFilter]. Tracking and category filtering use include/exclude id sets rather than a tri-state.
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
 * Per-entry accessors [libraryFilterMatches] reads, so the filter never depends on the concrete row type.
 * Both libraries bind them over the shared `LibraryItem` row, through [libraryItemFilterFields].
 * The per-type seams live here: [isDownloaded] folds in manga's local-source concept (novels have none),
 * [isLewd] folds in manga's source-name check (novels are genre-only), and [trackerIds] is each side's
 * merge-group union.
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
