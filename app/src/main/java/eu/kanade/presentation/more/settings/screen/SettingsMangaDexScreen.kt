package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import exh.md.MangaDexSyncWorker
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import mihon.app.di.appGraph
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

    // Hidden until a MangaDex language source is enabled. The lookup awaits the extension scan, so
    // this suspends rather than blocking whoever asks. Not a composable, so Injekt survives here
    // purely as a Context locator, the same shape as SettingsEhScreen.
    override suspend fun isEnabled(): Boolean = MdUtil.getEnabledMangaDex(Injekt.get<Context>()) != null

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        val reikaiSourcePreferences = remember { context.appGraph.reikaiSourcePreferences }
        val trackerManager = remember { context.appGraph.trackerManager }

        val enabledMangaDexs by produceState(initialValue = emptyList()) {
            value = MdUtil.getEnabledMangaDexs(sourcePreferences, context.appGraph.sourceManager)
        }
        val languageEntries = buildMap {
            put("0", stringResource(MR.strings.md_first_enabled_source))
            enabledMangaDexs.forEach { source ->
                put(source.id.toString(), source.toString())
            }
        }

        // UNFOLLOWED is not a syncable status. Keys are the status values the sync worker reads back.
        val mdList = trackerManager.mdList
        val statusEntries = mdList.getStatusList()
            .filter { it != FollowStatus.UNFOLLOWED.long }
            .mapNotNull { status -> mdList.getStatus(status)?.let { status.toString() to stringResource(it) } }
            .toMap()

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
                onClick = { startSync(context, trackerManager, MangaDexSyncWorker.Target.SYNC_FOLLOWS) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_mangadex_push_favorites_to_mangadex),
                subtitle = stringResource(MR.strings.pref_mangadex_push_favorites_to_mangadex_summary),
                onClick = { startSync(context, trackerManager, MangaDexSyncWorker.Target.PUSH_FAVORITES) },
            ),
        )
    }

    // Both sync actions need the MDList account; nudge the user to Tracking if not signed in yet.
    private fun startSync(context: Context, trackerManager: TrackerManager, target: MangaDexSyncWorker.Target) {
        if (!trackerManager.mdList.isLoggedIn) {
            context.toast(MR.strings.pref_mangadex_sign_in_required)
        } else if (MangaDexSyncWorker.startNow(context, target)) {
            context.toast(MR.strings.pref_mangadex_sync_started)
        } else {
            context.toast(MR.strings.pref_mangadex_sync_already_running)
        }
    }
}
