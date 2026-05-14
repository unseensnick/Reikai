package eu.kanade.tachiyomi.ui.setting.controllers

import android.content.Context
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import yokai.domain.library.taste.TrackerLibraryRepository
import yokai.domain.library.taste.interactor.RefreshTrackerLibrary
import yokai.i18n.MR
import yokai.util.lang.getString
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

/**
 * Settings → Library → Recommendations.
 *
 * Three sections:
 * 1. **Recommendation sources** (Phase 3) — master toggle + per-tracker sources for the
 *    related-mangas carousel.
 * 2. **Taste profile** (Phase 4 core) — per-tracker library-pull toggles, auto-refresh
 *    interval, refresh-now button, and a last-refresh summary line.
 * 3. **Candidate injection** (Phase 5) — sub-toggles for the taste-profile-driven candidate
 *    streams that feed the related-mangas carousel: tag-search on the current source and
 *    cross-recommendation from the user's top-rated tracked manga.
 */
class SettingsLibraryRecommendationsController : SettingsLegacyController() {

    private val trackManager: TrackManager by injectLazy()
    private val refreshTrackerLibrary: RefreshTrackerLibrary by injectLazy()
    private val trackerLibraryRepository: TrackerLibraryRepository by injectLazy()

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

        preferenceCategory {
            titleRes = MR.strings.taste_profile

            pullLibraryToggle(Keys.pullLibraryFromAnilist, trackManager.aniList, MR.strings.anilist)
            pullLibraryToggle(Keys.pullLibraryFromMyAnimeList, trackManager.myAnimeList, MR.strings.myanimelist)
            pullLibraryToggle(Keys.pullLibraryFromKitsu, trackManager.kitsu, MR.strings.kitsu)

            intListPreference(activity) {
                key = Keys.trackerLibraryAutoRefreshHours
                titleRes = MR.strings.auto_refresh_tracker_library
                entriesRes = arrayOf(MR.strings.never, MR.strings.every_7_days, MR.strings.every_30_days)
                entryValues = listOf(0, AUTO_REFRESH_7_DAYS_HOURS, AUTO_REFRESH_30_DAYS_HOURS)
                defaultValue = 0
            }

            preference {
                key = "tracker_library_refresh_now"
                isPersistent = false
                titleRes = MR.strings.refresh_now
                summaryRes = MR.strings.refresh_now_summary

                preferences.trackerLibraryRefreshCooldownUntil().changesIn(viewScope) { cooldownUntil ->
                    isEnabled = System.currentTimeMillis() >= cooldownUntil
                }

                onClick {
                    val now = System.currentTimeMillis()
                    val cooldownUntil = preferences.trackerLibraryRefreshCooldownUntil().get()
                    if (now < cooldownUntil) return@onClick
                    preferences.trackerLibraryRefreshCooldownUntil().set(now + REFRESH_COOLDOWN_MS)
                    viewScope.launch {
                        refreshTrackerLibrary.await()
                        // Bump the cooldown pref again to retrigger the last-refresh summary listener.
                        preferences.trackerLibraryRefreshCooldownUntil().set(System.currentTimeMillis() + REFRESH_COOLDOWN_MS)
                    }
                }
            }

            preference {
                key = "tracker_library_last_refresh_summary"
                isPersistent = false
                isSelectable = false
                titleRes = MR.strings.last_refresh
                val prefContext = context
                fun refresh() {
                    viewScope.launch {
                        summary = buildLastRefreshSummary(prefContext)
                    }
                }
                refresh()
                // Each refresh-button press flips the cooldown pref twice; each flip wakes this.
                preferences.trackerLibraryRefreshCooldownUntil().changesIn(viewScope) { refresh() }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.candidate_injection

            switchPreference {
                key = Keys.injectTagSearchCandidates
                titleRes = MR.strings.tag_search_on_current_source
                summaryRes = MR.strings.tag_search_on_current_source_summary
                defaultValue = true
            }

            switchPreference {
                key = Keys.injectCrossRecommendationCandidates
                titleRes = MR.strings.cross_recommendation_from_favorites
                summaryRes = MR.strings.cross_recommendation_from_favorites_summary
                defaultValue = true
            }
        }
    }

    private fun PreferenceGroup.pullLibraryToggle(
        prefKey: String,
        tracker: TrackService,
        trackerName: StringResource,
    ) {
        switchPreference {
            key = prefKey
            titleRes = trackerName
            defaultValue = false
            // Snapshot of login state at attach. If the user logs in/out without leaving
            // settings, they'll see the updated state on next entry.
            isEnabled = tracker.isLogged
            if (!tracker.isLogged) summaryRes = MR.strings.tracker_not_logged_in
        }
    }

    private suspend fun buildLastRefreshSummary(context: Context): String {
        val now = System.currentTimeMillis()
        // Pre-fetch all per-tracker timestamps outside the joinToString lambda — joinToString
        // takes a non-suspend transform, so the suspend repository call can't happen inside it.
        val rows = LIBRARY_TRACKERS.map { (trackerId, nameRes) ->
            context.getString(nameRes) to trackerLibraryRepository.lastFetchedAt(trackerId)
        }
        return rows.joinToString(" • ") { (name, fetchedAt) ->
            val whenStr = when {
                fetchedAt == null -> context.getString(MR.strings.last_refresh_never)
                else -> {
                    val days = TimeUnit.MILLISECONDS.toDays(now - fetchedAt).coerceAtLeast(0)
                    when (days) {
                        0L -> context.getString(MR.strings.last_refresh_today)
                        1L -> context.getString(MR.strings.last_refresh_yesterday)
                        else -> context.getString(MR.strings.last_refresh_days_ago, days)
                    }
                }
            }
            "$name: $whenStr"
        }
    }

    companion object {
        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val AUTO_REFRESH_7_DAYS_HOURS = 168
        private const val AUTO_REFRESH_30_DAYS_HOURS = 720

        // Tracker ids paired with their display name resource. Order matches the toggle order above.
        private val LIBRARY_TRACKERS = listOf(
            TrackManager.ANILIST to MR.strings.anilist,
            TrackManager.MYANIMELIST to MR.strings.myanimelist,
            TrackManager.KITSU to MR.strings.kitsu,
        )
    }
}
