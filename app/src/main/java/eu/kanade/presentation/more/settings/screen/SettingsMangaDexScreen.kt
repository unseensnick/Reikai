package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import exh.md.MangaDexSyncJob
import exh.md.utils.MdUtil
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * MangaDex enhanced-source hub: language target, follow-status filter, and the two-way library sync
 * actions. Login lives under Settings > Tracking (the MDList tracker), reached via the Account row.
 * Hidden until a MangaDex language source is enabled.
 */
object SettingsMangaDexScreen : SearchableSettings {
    private fun readResolve(): Any = SettingsMangaDexScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_mangadex

    // Top-level category, hidden until a MangaDex language source is enabled.
    override fun isEnabled(): Boolean = MdUtil.getEnabledMangaDex() != null

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val sourcePreferences: SourcePreferences = remember { Injekt.get() }
        val reikaiSourcePreferences: ReikaiSourcePreferences = remember { Injekt.get() }
        val trackerManager: TrackerManager = remember { Injekt.get() }

        val languageEntries = buildMap {
            put("0", stringResource(MR.strings.md_first_enabled_source))
            MdUtil.getEnabledMangaDexs(sourcePreferences).forEach { source ->
                put(source.id.toString(), source.toString())
            }
        }

        // FollowStatus READING..RE_READING (1..6); UNFOLLOWED (0) is not a syncable status.
        val statusEntries = mapOf(
            "1" to stringResource(MR.strings.reading),
            "2" to stringResource(MR.strings.completed),
            "3" to stringResource(MR.strings.on_hold),
            "4" to stringResource(MR.strings.plan_to_read),
            "5" to stringResource(MR.strings.dropped),
            "6" to stringResource(MR.strings.repeating),
        )

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_mangadex_account),
                subtitle = stringResource(MR.strings.pref_mangadex_account_summary),
                onClick = { navigator.push(SettingsTrackingScreen) },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = reikaiSourcePreferences.preferredMangaDexId,
                entries = languageEntries,
                title = stringResource(MR.strings.pref_mangadex_preferred_source),
            ),
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = reikaiSourcePreferences.mangadexSyncToLibraryIndexes,
                entries = statusEntries,
                title = stringResource(MR.strings.pref_mangadex_sync_follow_statuses),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_mangadex_sync_follows_to_library),
                subtitle = stringResource(MR.strings.pref_mangadex_sync_follows_to_library_summary),
                onClick = { startSync(context, trackerManager, MangaDexSyncJob.Target.SYNC_FOLLOWS) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_mangadex_push_favorites_to_mangadex),
                subtitle = stringResource(MR.strings.pref_mangadex_push_favorites_to_mangadex_summary),
                onClick = { startSync(context, trackerManager, MangaDexSyncJob.Target.PUSH_FAVORITES) },
            ),
        )
    }

    // Both sync actions need the MDList account; nudge the user to Tracking if not signed in yet.
    private fun startSync(context: Context, trackerManager: TrackerManager, target: MangaDexSyncJob.Target) {
        if (trackerManager.mdList.isLoggedIn) {
            MangaDexSyncJob.startNow(context, target)
            context.toast(MR.strings.pref_mangadex_sync_started)
        } else {
            context.toast(MR.strings.pref_mangadex_sign_in_required)
        }
    }
}
