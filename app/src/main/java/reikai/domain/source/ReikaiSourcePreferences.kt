package reikai.domain.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ContentType
import reikai.presentation.recents.RecentsMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * Reikai's net-new Browse-scoped preferences. Kept separate from Mihon's
 * [eu.kanade.domain.source.service.SourcePreferences] so Mihon's class stays untouched and
 * upstream-mergeable.
 */
@Inject
@SingleIn(AppScope::class)
class ReikaiSourcePreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Sticky content-type filter on the Browse tabs (Sources + Extensions). Its own key, distinct
     * from the Library filter, so each surface remembers its last type independently.
     */
    val browseContentType: Preference<ContentType> =
        preferenceStore.getEnum("browse_content_type", ContentType.ALL)

    /** Sticky content-type filter on the unified download queue (manga + novels), its own key. */
    val downloadContentType: Preference<ContentType> =
        preferenceStore.getEnum("download_content_type", ContentType.ALL)

    /** User pause on the novel downloader, persisted so a paused queue stays paused across restart
     *  (the manga side gets this free from WorkManager; the novel job auto-starts on init, so it needs
     *  an explicit flag). */
    val novelDownloadsPaused: Preference<Boolean> =
        preferenceStore.getBoolean("novel_downloads_paused", false)

    /**
     * Pinned light-novel source ids. Novel twin of
     * [eu.kanade.domain.source.service.SourcePreferences.pinnedSources]: pinned sources rise to a
     * "Pinned" section on the Sources list and back the global-search Pinned filter.
     */
    val pinnedNovelSources: Preference<Set<String>> =
        preferenceStore.getStringSet("ln_pinned_sources", emptySet())

    /**
     * Disabled (hidden) light-novel source ids. Novel twin of
     * [eu.kanade.domain.source.service.SourcePreferences.disabledSources]: a disabled source is hidden
     * from the Sources list and global search but stays installed and auto-updating.
     */
    val disabledNovelSources: Preference<Set<String>> =
        preferenceStore.getStringSet("ln_disabled_sources", emptySet())

    /**
     * Disabled light-novel source LANGUAGES. Novel twin of
     * [eu.kanade.domain.source.service.SourcePreferences.enabledLanguages], inverted: manga stores the enabled set
     * because its language universe is known up front, while novel languages arrive with whatever plugins the user
     * installs, so a deny-list keeps every language on by default and a newly appearing language visible without a
     * migration. A disabled language hides its sources from the Sources list and global search; they stay installed
     * and re-enable from the filter screen.
     */
    val disabledNovelLanguages: Preference<Set<String>> =
        preferenceStore.getStringSet("ln_disabled_languages", emptySet())

    /**
     * Ordered novel source ids picked in the migration pre-step. Novel twin of
     * [eu.kanade.domain.source.service.SourcePreferences.migrationSources]: the selection and its
     * priority order drive which sources a migration searches (and so which match it suggests first).
     * Order matters, so it is a List, stored newline-joined (source ids never contain newlines).
     */
    val novelMigrationSources: Preference<List<String>> = preferenceStore.getObjectFromString(
        "ln_migration_sources",
        emptyList(),
        { it.joinToString("\n") },
        { if (it.isEmpty()) emptyList() else it.split("\n") },
    )

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

    /**
     * Sticky content-type filter on the combined Recents tab. Its own key rather than a shared one:
     * with that tab off, Updates and History are two independent surfaces and linking their chips
     * would move something for people who never opted in.
     */
    val recentsContentType: Preference<ContentType> =
        preferenceStore.getEnum("recents_content_type", ContentType.ALL)

    /**
     * Which mode the combined Recents tab was last showing. One key rather than one per surface: the
     * two single-mode surfaces have nothing to remember, and an engine ignores a stored mode it does
     * not render. Stored by constant name, so [reikai.presentation.recents.RecentsMode]'s names are
     * the on-disk format.
     */
    val recentsMode: Preference<RecentsMode> =
        preferenceStore.getEnum("recents_mode", RecentsMode.FEED)

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

    // region Recents category filter

    /**
     * Include/exclude category filter for the recent-activity surface: one selection per rendered
     * surface, not per content type. The category table has one id space (the unification offset
     * novel ids so they cannot collide with manga ones), so a manga-only category simply matches no
     * novel rather than needing a set of its own. One set per surface for the same reason the chips
     * have one each: with the combined Recents tab off, Updates and History are independent. Resolved
     * by surface in [RecentsCategoryFilter]; empty means no constraint.
     */
    val updatesFilterCategories: Preference<Boolean> =
        preferenceStore.getBoolean("updates_filter_categories_enabled", false)

    val updatesFilterCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("updates_filter_categories_include", emptySet())
    val updatesFilterCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("updates_filter_categories_exclude", emptySet())

    val historyFilterCategories: Preference<Boolean> =
        preferenceStore.getBoolean("history_filter_categories_enabled", false)

    val historyFilterCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("history_filter_categories_include", emptySet())
    val historyFilterCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("history_filter_categories_exclude", emptySet())

    val recentsFilterCategories: Preference<Boolean> =
        preferenceStore.getBoolean("recents_filter_categories_enabled", false)

    val recentsFilterCategoriesInclude: Preference<Set<String>> =
        preferenceStore.getStringSet("recents_filter_categories_include", emptySet())
    val recentsFilterCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet("recents_filter_categories_exclude", emptySet())

    /**
     * Whether the combined modes keep a series with nothing left to read. Off by default, matching
     * Yokai's `show_read_in_all_recents`: those modes answer "what do I read next", which a caught-up
     * series has no answer to. The single-lane Updates and History modes ignore this, since they are
     * a record of what happened rather than a suggestion.
     */
    val recentsShowRead: Preference<Boolean> =
        preferenceStore.getBoolean("recents_show_read", false)

    /** Collapse a series' multiple same-date new chapters into one expandable row on the Updates tab. */
    val updatesGroupBySeries: Preference<Boolean> =
        preferenceStore.getBoolean("updates_group_by_series", false)

    // endregion

    // region MangaDex enhanced source

    /**
     * Which enabled MangaDex language source the enhanced-source settings and sync actions target when
     * several are enabled, stored as the source id string ("0" = first enabled). Reikai twin of
     * Komikku's SourcePreferences.preferredMangaDexId.
     */
    val preferredMangaDexId: Preference<String> =
        preferenceStore.getString("preferred_mangadex_id", "0")

    /**
     * Follow statuses the "Sync Follows to Library" action imports, stored as FollowStatus int values
     * (0..6). Defaults to reading + re-reading, matching Komikku's "only add reading or rereading"
     * intent. Reikai twin of Komikku's SourcePreferences.mangadexSyncToLibraryIndexes.
     */
    val mangadexSyncToLibraryIndexes: Preference<Set<String>> =
        preferenceStore.getStringSet("mangadex_sync_to_library_indexes", setOf("1", "6"))

    // endregion

    companion object {
        // Retired per-content-type Updates category-filter keys and their master switch: the filter
        // became one selection per rendered surface over the shared category id space. Values dropped,
        // not migrated (a filter is casually re-picked, and the switch defaults off); skipped on
        // restore so an old backup can't resurrect them. This retirement is why the live master
        // switches carry an `_enabled` suffix: the bare `updates_filter_categories` string is taken.
        const val DEAD_UPDATES_FILTER_CATEGORIES_KEY = "updates_filter_categories"
        const val DEAD_UPDATES_FILTER_CATEGORY_SET_PREFIX = "updates_filter_manga_categories_"
        const val DEAD_UPDATES_FILTER_NOVEL_CATEGORY_SET_PREFIX = "updates_filter_novel_categories_"
    }
}
