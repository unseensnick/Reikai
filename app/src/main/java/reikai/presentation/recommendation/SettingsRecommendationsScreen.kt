package reikai.presentation.recommendation

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import reikai.data.recommendation.taste.TrackerLibraryRefreshJob
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.RelatedPlacement
import reikai.domain.recommendation.TrackerToggle
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.recommendation.taste.TrackerLibraryFetcher
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Settings -> Library -> Recommendations. Net-new settings screen following Mihon's Preference
 * DSL. "Show related manga" is the master switch: with it off the carousel does no work at all, so
 * every other group here is hidden rather than left dangling.
 */
object SettingsRecommendationsScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.pref_recommendations

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { context.appGraph.reikaiRecommendationPreferences }
        val trackerManager = remember { context.appGraph.trackerManager }
        // Taste injection is tracker-derived, so the master "Tracker recommendations" toggle gates the
        // whole section: hidden (and skipped at load time) when the master is off, so "off" reads as a
        // source-only carousel with no dangling controls.
        val includeTrackers by prefs.includeTrackerRecommendations.collectAsState()
        // With the carousel off every other control here is dead, so only the master switch shows.
        val relatedEnabled by prefs.enableRelatedMangas.collectAsState()

        // Candidate injection needs the manga tracked on a recs-capable tracker, so it's only useful
        // (and only shown) when the user is logged into one.
        val recsTrackerLoggedIn = prefs.recommendationToggles(trackerManager).any { it.tracker.isLoggedIn }

        return listOfNotNull(
            sourcesGroup(prefs, trackerManager),
            tasteProfileGroup(prefs).takeIf { relatedEnabled },
            injectionGroup(prefs).takeIf { recsTrackerLoggedIn && includeTrackers && relatedEnabled },
            rerankingGroup(prefs).takeIf { relatedEnabled },
            filtersGroup(prefs).takeIf { relatedEnabled },
        )
    }

    @Composable
    private fun injectionGroup(prefs: ReikaiRecommendationPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_recommendation_injection),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.injectCrossRecommendationCandidates,
                    title = stringResource(MR.strings.pref_inject_cross_recommendation),
                    subtitle = stringResource(MR.strings.pref_inject_cross_recommendation_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.injectTagSearchCandidates,
                    title = stringResource(MR.strings.pref_inject_tag_search),
                    subtitle = stringResource(MR.strings.pref_inject_tag_search_summary),
                ),
            ),
        )

    @Composable
    private fun sourcesGroup(
        prefs: ReikaiRecommendationPreferences,
        trackerManager: TrackerManager,
    ): Preference.PreferenceGroup {
        val includeTrackers by prefs.includeTrackerRecommendations.collectAsState()
        val relatedEnabled by prefs.enableRelatedMangas.collectAsState()
        fun trackerToggle(toggle: TrackerToggle) =
            Preference.PreferenceItem.SwitchPreference(
                preference = toggle.preference,
                title = toggle.tracker.name,
                enabled = relatedEnabled && includeTrackers,
            )
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_recommendation_sources),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableRelatedMangas,
                    title = stringResource(MR.strings.pref_enable_related_mangas),
                    subtitle = stringResource(MR.strings.pref_enable_related_mangas_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.relatedPlacement,
                    entries = RelatedPlacement.entries.associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_related_placement),
                ).takeIf { relatedEnabled },
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.includeTrackerRecommendations,
                    title = stringResource(MR.strings.pref_include_tracker_recommendations),
                    subtitle = stringResource(MR.strings.pref_include_tracker_recommendations_summary),
                ).takeIf { relatedEnabled },
            ) + prefs.recommendationToggles(trackerManager).map(::trackerToggle).takeIf { relatedEnabled }.orEmpty(),
        )
    }

    @Composable
    private fun tasteProfileGroup(prefs: ReikaiRecommendationPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val repository = remember { context.appGraph.tasteLibraryRepository }
        val fetchers = remember { context.appGraph.trackerLibraryFetchers }
        val neverLabel = stringResource(MR.strings.pref_last_refresh_never)
        // Re-read whenever the manual pull starts or ends, so the summary updates as it lands. withIOContext
        // because a produceState body runs on Main, and this is one DB read per tracker.
        val lastRefreshSummary by produceState("", neverLabel) {
            TrackerLibraryRefreshJob.isRunningFlow(context).collect {
                value = withIOContext { buildLastRefreshSummary(repository, fetchers, neverLabel) }
            }
        }

        // enabled = visible in Mihon's preference DSL, so a tracker's pull toggle only appears once
        // the user is logged into it (the pull needs their private library, which login gates).
        fun pullToggle(fetcher: TrackerLibraryFetcher) =
            Preference.PreferenceItem.SwitchPreference(
                preference = fetcher.pullPreference,
                title = fetcher.tracker.name,
                enabled = fetcher.tracker.isLoggedIn,
            )
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_taste_profile),
            preferenceItems = fetchers.map(::pullToggle) + listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.trackerLibraryAutoRefreshHours,
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        168 to stringResource(MR.strings.update_weekly),
                        720 to stringResource(MR.strings.pref_update_monthly),
                    ),
                    title = stringResource(MR.strings.pref_tracker_library_auto_refresh),
                    onValueChanged = {
                        TrackerLibraryRefreshJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_refresh_now),
                    // Per-tracker last-pull times under the title, so it actually tells the user something.
                    subtitle = lastRefreshSummary.ifBlank { stringResource(MR.strings.pref_refresh_now_summary) },
                    onClick = {
                        context.toast(
                            if (TrackerLibraryRefreshJob.startNow(context)) {
                                MR.strings.pref_refresh_now_started
                            } else {
                                MR.strings.pref_refresh_now_cooldown
                            },
                        )
                    },
                ),
            ),
        )
    }

    /** One line per logged-in library tracker: "AniList: 3 days ago". Empty when none are logged in. */
    private suspend fun buildLastRefreshSummary(
        repository: TasteLibraryRepository,
        fetchers: List<TrackerLibraryFetcher>,
        neverLabel: String,
    ): String {
        val now = System.currentTimeMillis()
        // lastFetch is a suspend DB read, so resolve every timestamp before the non-suspend join.
        val rows = fetchers.map { it.tracker }
            .filter { it.isLoggedIn }
            .map { it.name to repository.lastFetch(it.id) }
        if (rows.isEmpty()) return ""
        return rows.joinToString("\n") { (name, fetchedAt) ->
            val whenStr = if (fetchedAt == null) {
                neverLabel
            } else {
                DateUtils.getRelativeTimeSpanString(fetchedAt, now, DateUtils.DAY_IN_MILLIS).toString()
            }
            "$name: $whenStr"
        }
    }

    @Composable
    private fun rerankingGroup(prefs: ReikaiRecommendationPreferences): Preference.PreferenceGroup {
        val rerank by prefs.enableRecommendationRerank.collectAsState()
        val style by prefs.recommendationStyle.collectAsState()
        val serendipity by prefs.serendipity.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_recommendation_reranking),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableRecommendationRerank,
                    title = stringResource(MR.strings.pref_enable_recommendation_rerank),
                    subtitle = stringResource(MR.strings.pref_enable_recommendation_rerank_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = style,
                    title = stringResource(MR.strings.pref_recommendation_style),
                    valueString = "$style%",
                    valueRange = 0..100,
                    enabled = rerank,
                    onValueChanged = { prefs.recommendationStyle.set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = serendipity,
                    title = stringResource(MR.strings.pref_serendipity),
                    valueString = "$serendipity%",
                    valueRange = 0..100,
                    enabled = rerank,
                    onValueChanged = { prefs.serendipity.set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun filtersGroup(prefs: ReikaiRecommendationPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_recommendation_filters),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.hideInLibraryRecommendations,
                    title = stringResource(MR.strings.pref_hide_in_library_recommendations),
                    subtitle = stringResource(MR.strings.pref_hide_in_library_recommendations_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.hideTrackedReadingCompleted,
                    title = stringResource(MR.strings.pref_hide_tracked_reading_completed),
                    subtitle = stringResource(MR.strings.pref_recommendation_filters_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.hideTrackedDropped,
                    title = stringResource(MR.strings.pref_hide_tracked_dropped),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.hideTrackedOnHold,
                    title = stringResource(MR.strings.pref_hide_tracked_on_hold),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.hideTrackedPlanToRead,
                    title = stringResource(MR.strings.pref_hide_tracked_plan_to_read),
                ),
            ),
        )
}
