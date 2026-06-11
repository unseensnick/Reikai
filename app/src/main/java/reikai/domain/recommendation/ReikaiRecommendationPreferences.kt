package reikai.domain.recommendation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Net-new preferences for the recommendation carousel + taste profile. Key strings are preserved
 * from the Yōkai-era fork where they existed (so an in-place upgrade keeps the user's choices);
 * the new external trackers (Shikimori recs, Shikimori/Bangumi library pull) get net-new keys.
 *
 * MangaUpdates is recommendations-only (its user-library API is undocumented), so it has a recs
 * toggle but no library-pull toggle. Kitsu and Bangumi have no recommendations endpoint, so they
 * have a library-pull toggle but no recs toggle.
 */
class ReikaiRecommendationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // region Recommendation sources (per-manga "Related")

    /** Master switch for tracker-origin recommendation candidates. */
    val includeTrackerRecommendations: Preference<Boolean> =
        preferenceStore.getBoolean("pref_include_tracker_recommendations", true)

    val anilistRecommendations: Preference<Boolean> =
        preferenceStore.getBoolean("pref_anilist_recommendations", true)

    val myAnimeListRecommendations: Preference<Boolean> =
        preferenceStore.getBoolean("pref_myanimelist_recommendations", true)

    val mangaUpdatesRecommendations: Preference<Boolean> =
        preferenceStore.getBoolean("pref_mangaupdates_recommendations", true)

    val shikimoriRecommendations: Preference<Boolean> =
        preferenceStore.getBoolean("pref_shikimori_recommendations", true)

    // endregion

    // region Taste profile (pull the user's tracker library to learn tag affinity)

    val pullLibraryFromAnilist: Preference<Boolean> =
        preferenceStore.getBoolean("pullLibraryFromAnilist", false)

    val pullLibraryFromMyAnimeList: Preference<Boolean> =
        preferenceStore.getBoolean("pullLibraryFromMyAnimeList", false)

    val pullLibraryFromKitsu: Preference<Boolean> =
        preferenceStore.getBoolean("pullLibraryFromKitsu", false)

    val pullLibraryFromShikimori: Preference<Boolean> =
        preferenceStore.getBoolean("pullLibraryFromShikimori", false)

    val pullLibraryFromBangumi: Preference<Boolean> =
        preferenceStore.getBoolean("pullLibraryFromBangumi", false)

    /** Auto-refresh interval for the tracker-library pull, in hours. 0 = never (manual only). */
    val trackerLibraryAutoRefreshHours: Preference<Int> =
        preferenceStore.getInt("pref_tracker_library_auto_refresh_hours", 0)

    // endregion

    // region Candidate injection (taste-driven extra candidates)

    val injectTagSearchCandidates: Preference<Boolean> =
        preferenceStore.getBoolean("pref_inject_tag_search_candidates", true)

    val injectCrossRecommendationCandidates: Preference<Boolean> =
        preferenceStore.getBoolean("pref_inject_cross_recommendation_candidates", true)

    // endregion

    // region Reranking

    /** Master switch for taste-driven reordering of the carousel. */
    val enableRecommendationRerank: Preference<Boolean> =
        preferenceStore.getBoolean("pref_enable_recommendation_rerank", true)

    /** 0..100: 0 = popularity-first, 100 = fully personalized. Maps to the ranker's wPersonal. */
    val recommendationStyle: Preference<Int> =
        preferenceStore.getInt("pref_recommendation_style", 25)

    /** 0..100: 0 = familiar, 100 = adventurous. Maps to the ranker's wSerendipity. */
    val serendipity: Preference<Int> =
        preferenceStore.getInt("pref_serendipity", 20)

    // endregion

    // region Filters (hide tracker-origin candidates the user already tracks)

    val hideTrackedReadingCompleted: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_tracked_reading_completed", true)

    val hideTrackedDropped: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_tracked_dropped", true)

    val hideTrackedOnHold: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_tracked_on_hold", false)

    val hideTrackedPlanToRead: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_tracked_plan_to_read", false)

    // endregion

    /** Build a ranker from the current style/serendipity prefs (0..100 mapped to 0..1 weights). */
    fun buildRanker(): RecommendationRanker = RecommendationRanker(
        wPersonal = recommendationStyle.get().coerceIn(0, 100) / 100.0,
        wSerendipity = serendipity.get().coerceIn(0, 100) / 100.0,
    )
}
