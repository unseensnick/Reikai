package eu.kanade.domain.track.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

@Inject
@SingleIn(AppScope::class)
class TrackPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun trackUsername(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_username_${tracker.id}"),
        "",
    )

    fun trackDisplayUsername(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_displayname_${tracker.id}"),
        "",
    )

    fun trackPassword(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_password_${tracker.id}"),
        "",
    )

    fun trackAuthExpired(tracker: Tracker) = preferenceStore.getBoolean(
        Preference.privateKey("pref_tracker_auth_expired_${tracker.id}"),
        false,
    )

    fun setCredentials(tracker: Tracker, username: String, password: String) {
        trackUsername(tracker).set(username)
        trackPassword(tracker).set(password)
        trackAuthExpired(tracker).set(false)
    }

    fun trackToken(tracker: Tracker) = preferenceStore.getString(Preference.privateKey("track_token_${tracker.id}"), "")

    val anilistScoreType: Preference<String> = preferenceStore.getString("anilist_score_type", Anilist.POINT_10)

    val kitsuScoreType: Preference<String> = preferenceStore.getString("kitsu_score_type", Kitsu.RATING_ADVANCED)

    val mangabakaScoreType: Preference<String> = preferenceStore.getString("mangabaka_score_type", MangaBaka.STEP_1)

    // RK: whether reading a novel pushes its status to RanobeDB. On, because a read-driven push only
    // happens when the status actually moves, so it costs about one write per series rather than one
    // per chapter. Off, the status still reached the site eventually, carried by an unrelated score
    // or date edit, which is more confusing than either always or never.
    val ranobeDbSyncWhileReading: Preference<Boolean> =
        preferenceStore.getBoolean("ranobedb_sync_while_reading", true)

    // RK: NovelList's API host, editable because it is a generated Cloud Run hostname carrying their
    // project number rather than a domain they own. A move would otherwise brick the tracker with no
    // way out but an app update. Blank means the built-in default.
    val novelListApiUrl: Preference<String> = preferenceStore.getString("novellist_api_url", "")

    val autoUpdateTrack: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    val autoUpdateTrackOnMarkRead: Preference<AutoTrackState> = preferenceStore.getEnum(
        "pref_auto_update_manga_on_mark_read",
        AutoTrackState.ALWAYS,
    )
}
