package reikai.domain.source

import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * Reikai's net-new Browse-scoped preferences. Kept separate from Mihon's
 * [eu.kanade.domain.source.service.SourcePreferences] so Mihon's class stays untouched and
 * upstream-mergeable.
 */
class ReikaiSourcePreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Sticky content-type filter on the Browse tabs (Sources + Extensions). Its own key, distinct
     * from the Library filter (S6), so each surface remembers its last type independently.
     */
    val browseContentType: Preference<ContentType> =
        preferenceStore.getEnum("browse_content_type", ContentType.ALL)

    /** Sticky content-type filter on the unified download queue (manga + novels), its own key. */
    val downloadContentType: Preference<ContentType> =
        preferenceStore.getEnum("download_content_type", ContentType.ALL)

    /**
     * Pinned light-novel source ids. Novel twin of
     * [eu.kanade.domain.source.service.SourcePreferences.pinnedSources]: pinned sources rise to a
     * "Pinned" section on the Sources list and back the global-search Pinned filter.
     */
    val pinnedNovelSources: Preference<Set<String>> =
        preferenceStore.getStringSet("ln_pinned_sources", emptySet())

    /** "Has results" toggle on the novel global search (hide sources that returned nothing). Persisted,
     *  mirroring the manga global search's globalSearchFilterState. */
    val novelGlobalSearchHasResults: Preference<Boolean> =
        preferenceStore.getBoolean("ln_global_search_has_results", false)

    /** Sticky content-type filter on the Updates tab (manga + novels), its own key. */
    val updatesContentType: Preference<ContentType> =
        preferenceStore.getEnum("updates_content_type", ContentType.ALL)

    /** Sticky content-type filter on the History tab (manga + novels), its own key. */
    val historyContentType: Preference<ContentType> =
        preferenceStore.getEnum("history_content_type", ContentType.ALL)

    /** Sticky content-type filter on the Stats screen (manga + novels), its own key. */
    val statsContentType: Preference<ContentType> =
        preferenceStore.getEnum("stats_content_type", ContentType.ALL)

    /**
     * Display mode (comfortable / compact / list) for the per-source novel browse grid. Its own key,
     * separate from Mihon's manga catalogue mode (`pref_display_mode_catalogue`), so the two surfaces
     * remember independently. Stored via the [LibraryDisplayMode] serializer, mirroring the manga side.
     */
    val novelBrowseDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "reikai_novel_browse_display_mode",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    // region Updates category filter

    /**
     * Include/exclude category filter for the Updates tab. Mirrors the library's
     * [reikai.domain.library.ReikaiLibraryPreferences.filterCategories] dim, but manga and novels
     * have separate category systems (separate id spaces), so each type carries its own selections;
     * the picker applies them per content-type chip. Empty sets = no constraint for that type.
     */
    val updatesFilterCategories: Preference<Boolean> =
        preferenceStore.getBoolean("updates_filter_categories", false)

    val updatesFilterMangaCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("updates_filter_manga_categories_include", emptySet())
    val updatesFilterMangaCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("updates_filter_manga_categories_exclude", emptySet())

    val updatesFilterNovelCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("updates_filter_novel_categories_include", emptySet())
    val updatesFilterNovelCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("updates_filter_novel_categories_exclude", emptySet())

    /** Collapse a series' multiple same-date new chapters into one expandable row on the Updates tab. */
    val updatesGroupBySeries: Preference<Boolean> =
        preferenceStore.getBoolean("updates_group_by_series", false)

    // endregion
}
