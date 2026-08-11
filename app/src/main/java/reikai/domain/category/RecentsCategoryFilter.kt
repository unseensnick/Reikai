package reikai.domain.category

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.Preference

/**
 * Which recent-activity surface a category selection belongs to. One per rendered surface, not one
 * per content type and not one for the app: with the combined Recents tab off, Updates and History
 * are two tabs that look and behave independently, so linking their filters would move something for
 * someone who never opted in. Mirrors the content-type chip keys, which split for the same reason.
 * Nothing reads [RECENTS] until the combined tab ships.
 */
enum class RecentsSurface { UPDATES, HISTORY, RECENTS }

/**
 * One surface's category selection, resolved for its feed queries. Both lists are empty whenever
 * that surface's master toggle is off, because emptiness is the only "no constraint" signal a query
 * gets: the repositories derive the queries' `includedEmpty` / `excludedEmpty` flags from
 * [List.isEmpty], so a selection parked behind a disabled toggle has to arrive here already cleared.
 */
data class RecentsCategoryFilter(
    val include: List<Long> = emptyList(),
    val exclude: List<Long> = emptyList(),
) {
    /** Whether the filter is actually constraining the feeds, for the shells' empty state and tint. */
    val active: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty()
}

/**
 * The three preferences one surface's selection lives in: the master toggle, the included ids and the
 * excluded ids. Every read and every write resolves through here, so a picker opened on one surface
 * cannot reach another's keys, which is what keeps two separate tabs behaving like two separate tabs.
 */
internal fun ReikaiSourcePreferences.categoryFilterPrefs(
    surface: RecentsSurface,
): Triple<Preference<Boolean>, Preference<Set<String>>, Preference<Set<String>>> = when (surface) {
    RecentsSurface.UPDATES -> Triple(
        updatesFilterCategories,
        updatesFilterCategoriesInclude,
        updatesFilterCategoriesExclude,
    )
    RecentsSurface.HISTORY -> Triple(
        historyFilterCategories,
        historyFilterCategoriesInclude,
        historyFilterCategoriesExclude,
    )
    RecentsSurface.RECENTS -> Triple(
        recentsFilterCategories,
        recentsFilterCategoriesInclude,
        recentsFilterCategoriesExclude,
    )
}

/** One derivation for every recents feed; each model used to carry its own copy of this. */
fun ReikaiSourcePreferences.recentsCategoryFilterFlow(surface: RecentsSurface): Flow<RecentsCategoryFilter> {
    val (enabledPref, includePref, excludePref) = categoryFilterPrefs(surface)
    return combine(
        enabledPref.changes(),
        includePref.changes(),
        excludePref.changes(),
    ) { enabled, include, exclude ->
        if (!enabled) {
            RecentsCategoryFilter()
        } else {
            RecentsCategoryFilter(
                include = include.mapNotNull(String::toLongOrNull),
                exclude = exclude.mapNotNull(String::toLongOrNull),
            )
        }
    }
}
