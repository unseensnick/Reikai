package reikai.presentation.recommendation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import tachiyomi.core.common.preference.Preference as PreferenceData
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * RK: Settings -> Library -> Recommendations. Net-new settings screen following Mihon's Preference
 * DSL. Exposes the controls whose backends are live: recommendation sources, the taste-profile
 * library pull (+ refresh-now), and the taste reranking. Candidate-injection, status filters, and
 * the auto-refresh schedule land with their backends (R4c / R5).
 */
object SettingsRecommendationsScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.pref_recommendations

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<ReikaiRecommendationPreferences>() }
        val trackerManager = remember { Injekt.get<TrackerManager>() }

        return listOf(
            sourcesGroup(prefs, trackerManager),
            tasteProfileGroup(prefs, trackerManager),
            rerankingGroup(prefs),
        )
    }

    @Composable
    private fun sourcesGroup(
        prefs: ReikaiRecommendationPreferences,
        trackerManager: TrackerManager,
    ): Preference.PreferenceGroup {
        val includeTrackers by prefs.includeTrackerRecommendations.collectAsState()
        fun trackerToggle(tracker: Tracker, pref: PreferenceData<Boolean>) =
            Preference.PreferenceItem.SwitchPreference(
                preference = pref,
                title = tracker.name,
                enabled = includeTrackers,
            )
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_recommendation_sources),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.includeTrackerRecommendations,
                    title = stringResource(MR.strings.pref_include_tracker_recommendations),
                    subtitle = stringResource(MR.strings.pref_include_tracker_recommendations_summary),
                ),
                trackerToggle(trackerManager.aniList, prefs.anilistRecommendations),
                trackerToggle(trackerManager.myAnimeList, prefs.myAnimeListRecommendations),
                trackerToggle(trackerManager.mangaUpdates, prefs.mangaUpdatesRecommendations),
                trackerToggle(trackerManager.shikimori, prefs.shikimoriRecommendations),
            ),
        )
    }

    @Composable
    private fun tasteProfileGroup(
        prefs: ReikaiRecommendationPreferences,
        trackerManager: TrackerManager,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val refreshTrackerLibrary = remember { Injekt.get<RefreshTrackerLibrary>() }
        // Resolved here (composable scope) so the non-composable helper below can capture it.
        val loginRequired = stringResource(MR.strings.pref_taste_profile_login_required)
        fun pullToggle(tracker: Tracker, pref: PreferenceData<Boolean>) =
            Preference.PreferenceItem.SwitchPreference(
                preference = pref,
                title = tracker.name,
                // Only meaningful once logged in; greyed out otherwise.
                subtitle = if (tracker.isLoggedIn) null else loginRequired,
                enabled = tracker.isLoggedIn,
            )
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_taste_profile),
            preferenceItems = listOf(
                pullToggle(trackerManager.aniList, prefs.pullLibraryFromAnilist),
                pullToggle(trackerManager.myAnimeList, prefs.pullLibraryFromMyAnimeList),
                pullToggle(trackerManager.kitsu, prefs.pullLibraryFromKitsu),
                pullToggle(trackerManager.shikimori, prefs.pullLibraryFromShikimori),
                pullToggle(trackerManager.bangumi, prefs.pullLibraryFromBangumi),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_refresh_now),
                    subtitle = stringResource(MR.strings.pref_refresh_now_summary),
                    onClick = {
                        context.toast(MR.strings.pref_refresh_now_started)
                        scope.launch { refreshTrackerLibrary.await() }
                    },
                ),
            ),
        )
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
}
