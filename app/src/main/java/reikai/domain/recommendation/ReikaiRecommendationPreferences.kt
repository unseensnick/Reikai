package reikai.domain.recommendation

import eu.kanade.tachiyomi.data.track.TrackerManager
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

    // region Filters (hide already-tracked / in-library candidates from suggestions)

    /** Hide any suggestion that matches a manga already in the library (by normalized title),
     *  regardless of tracking. The simplest declutter; independent of the status filters below. */
    val hideInLibraryRecommendations: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_in_library_recommendations", false)

    val hideTrackedReadingCompleted: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_tracked_reading_completed", false)

    val hideTrackedDropped: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_tracked_dropped", false)

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

    /**
     * Tracker ids whose recommendation stream is currently enabled: the master toggle on, AND that
     * tracker's own sub-toggle on. Empty when the master toggle is off.
     *
     * Shared by both carousel paths (the title-search [RecommendationsFetcher] and the media-context
     * [RelatedMangasLoader]) so they can't disagree on what the "Tracker recommendations" toggles
     * gate. A media-context recommendation for a tracker not in this set is dropped from the pool.
     */
    fun enabledRecommendationTrackerIds(trackerManager: TrackerManager): Set<Long> {
        if (!includeTrackerRecommendations.get()) return emptySet()
        return buildSet {
            if (anilistRecommendations.get()) add(trackerManager.aniList.id)
            if (myAnimeListRecommendations.get()) add(trackerManager.myAnimeList.id)
            if (mangaUpdatesRecommendations.get()) add(trackerManager.mangaUpdates.id)
            if (shikimoriRecommendations.get()) add(trackerManager.shikimori.id)
        }
    }
}
