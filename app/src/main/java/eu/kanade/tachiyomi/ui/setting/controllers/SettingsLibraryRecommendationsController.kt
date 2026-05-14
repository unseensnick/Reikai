package eu.kanade.tachiyomi.ui.setting.controllers

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import yokai.i18n.MR
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

/**
 * Settings → Library → Recommendations.
 *
 * Master toggle for tracker-backed recommendations in the related-mangas carousel, plus
 * per-tracker sub-toggles (AniList / MyAnimeList / MangaUpdates). Sub-toggles disable when the
 * master is off. Future phases (taste-profile reranking, serendipity, etc.) will add rows here.
 */
class SettingsLibraryRecommendationsController : SettingsLegacyController() {

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.recommendations

        switchPreference {
            key = Keys.includeTrackerRecommendations
            titleRes = MR.strings.include_tracker_recommendations
            summaryRes = MR.strings.include_tracker_recommendations_summary
            defaultValue = true
        }

        preferenceCategory {
            titleRes = MR.strings.recommendation_sources

            switchPreference {
                key = Keys.aniListRecommendations
                titleRes = MR.strings.anilist
                defaultValue = true
                preferences.includeTrackerRecommendations().changesIn(viewScope) { isEnabled = it }
            }

            switchPreference {
                key = Keys.myAnimeListRecommendations
                titleRes = MR.strings.myanimelist
                defaultValue = true
                preferences.includeTrackerRecommendations().changesIn(viewScope) { isEnabled = it }
            }

            switchPreference {
                key = Keys.mangaUpdatesRecommendations
                titleRes = MR.strings.manga_updates
                defaultValue = true
                preferences.includeTrackerRecommendations().changesIn(viewScope) { isEnabled = it }
            }
        }
    }
}
