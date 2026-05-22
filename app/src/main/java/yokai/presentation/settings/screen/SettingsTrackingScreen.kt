package yokai.presentation.settings.screen

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.DialogHostState
import yokai.domain.simple
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.screen.tracking.awaitTrackLogin
import yokai.util.lang.getString

object SettingsTrackingScreen : ComposableSettings() {

    private fun readResolve() = SettingsTrackingScreen

    private const val LOG_TAG = "SettingsTrackingScreen"

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.tracking

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences: PreferencesHelper by injectLazy()
        val trackPreferences: TrackPreferences by injectLazy()
        val trackManager: TrackManager by injectLazy()
        val sourceManager: SourceManager by injectLazy()

        val context = LocalContext.current
        val dialogHostState = LocalDialogHostState.currentOrThrow
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            Logger.i(LOG_TAG) { "entered compose tracking settings" }
        }

        val anilistUsername by trackPreferences.trackUsername(trackManager.aniList).collectAsState()

        val enhancedTrackers = remember(trackManager, sourceManager) {
            trackManager.services.filter { service ->
                service is EnhancedTrackService &&
                    sourceManager.getCatalogueSources().any { service.accept(it) }
            }
        }

        return buildList<Preference> {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.autoUpdateTrack(),
                    title = stringResource(MR.strings.update_tracking_after_reading),
                    onValueChanged = {
                        Logger.i(LOG_TAG) { "autoUpdateTrack → $it" }
                        true
                    },
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.trackMarkedAsRead(),
                    title = stringResource(MR.strings.update_tracking_marked_read),
                    onValueChanged = {
                        Logger.i(LOG_TAG) { "trackMarkedAsRead → $it" }
                        true
                    },
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.syncTrackerLinksGrouped(),
                    title = stringResource(MR.strings.sync_tracker_links_grouped),
                    subtitle = stringResource(MR.strings.sync_tracker_links_grouped_summary),
                    onValueChanged = {
                        Logger.i(LOG_TAG) { "syncTrackerLinksGrouped → $it" }
                        true
                    },
                ),
            )

            add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.services),
                    preferenceItems = buildList<Preference.PreferenceItem<out Any>> {
                        add(
                            browserTracker(
                                service = trackManager.myAnimeList,
                                serviceTitle = stringResource(trackManager.myAnimeList.nameRes()),
                                authUrl = MyAnimeListApi.authUrl(),
                                context = context,
                                scope = scope,
                                dialogHostState = dialogHostState,
                            ),
                        )
                        add(
                            browserTracker(
                                service = trackManager.aniList,
                                serviceTitle = stringResource(trackManager.aniList.nameRes()),
                                authUrl = AnilistApi.authUrl(),
                                context = context,
                                scope = scope,
                                dialogHostState = dialogHostState,
                            ),
                        )
                        if (anilistUsername.isNotEmpty()) {
                            val anilistName = stringResource(trackManager.aniList.nameRes())
                            add(
                                Preference.PreferenceItem.TextPreference(
                                    title = stringResource(MR.strings.update_tracking_scoring_type, anilistName),
                                    onClick = {
                                        Logger.i(LOG_TAG) { "update_anilist_scoring clicked" }
                                        scope.launch {
                                            val (result, error) = withIOContext {
                                                trackManager.aniList.updatingScoring()
                                            }
                                            if (result) {
                                                context.toast(MR.strings.scoring_type_updated)
                                            } else {
                                                context.toast(
                                                    context.getString(
                                                        MR.strings.could_not_update_scoring_,
                                                        error?.localizedMessage.orEmpty(),
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                ),
                            )
                        }
                        add(
                            credentialsTracker(
                                service = trackManager.kitsu,
                                serviceTitle = stringResource(trackManager.kitsu.nameRes()),
                                usernameLabel = stringResource(MR.strings.email),
                                context = context,
                                scope = scope,
                                dialogHostState = dialogHostState,
                            ),
                        )
                        add(
                            credentialsTracker(
                                service = trackManager.mangaUpdates,
                                serviceTitle = stringResource(trackManager.mangaUpdates.nameRes()),
                                usernameLabel = stringResource(MR.strings.username),
                                context = context,
                                scope = scope,
                                dialogHostState = dialogHostState,
                            ),
                        )
                        add(
                            browserTracker(
                                service = trackManager.shikimori,
                                serviceTitle = stringResource(trackManager.shikimori.nameRes()),
                                authUrl = ShikimoriApi.authUrl(),
                                context = context,
                                scope = scope,
                                dialogHostState = dialogHostState,
                            ),
                        )
                        add(
                            browserTracker(
                                service = trackManager.bangumi,
                                serviceTitle = stringResource(trackManager.bangumi.nameRes()),
                                authUrl = BangumiApi.authUrl(),
                                context = context,
                                scope = scope,
                                dialogHostState = dialogHostState,
                            ),
                        )
                        add(
                            Preference.PreferenceItem.InfoPreference(
                                title = stringResource(MR.strings.tracking_info),
                            ),
                        )
                    }.toPersistentList(),
                ),
            )

            if (enhancedTrackers.isNotEmpty()) {
                add(
                    Preference.PreferenceGroup(
                        title = stringResource(MR.strings.enhanced_services),
                        preferenceItems = buildList<Preference.PreferenceItem<out Any>> {
                            enhancedTrackers.forEach { service ->
                                add(
                                    enhancedTracker(
                                        service = service as EnhancedTrackService,
                                        trackService = service as TrackService,
                                        serviceTitle = stringResource(service.nameRes()),
                                    ),
                                )
                            }
                            add(
                                Preference.PreferenceItem.InfoPreference(
                                    title = stringResource(MR.strings.enhanced_tracking_info),
                                ),
                            )
                        }.toPersistentList(),
                    ),
                )
            }
        }
    }

    private fun browserTracker(
        service: TrackService,
        serviceTitle: String,
        authUrl: Uri,
        context: android.content.Context,
        scope: CoroutineScope,
        dialogHostState: DialogHostState,
    ): Preference.PreferenceItem.TrackerPreference {
        return Preference.PreferenceItem.TrackerPreference(
            tracker = service,
            title = serviceTitle,
            login = {
                Logger.i(LOG_TAG) { "browser-login $serviceTitle" }
                context.openInBrowser(authUrl, service.getLogoColor(), true)
            },
            logout = {
                Logger.i(LOG_TAG) { "logout-clicked $serviceTitle" }
                scope.launch { confirmAndLogout(service, serviceTitle, context, dialogHostState) }
            },
        )
    }

    private fun credentialsTracker(
        service: TrackService,
        serviceTitle: String,
        usernameLabel: String,
        context: android.content.Context,
        scope: CoroutineScope,
        dialogHostState: DialogHostState,
    ): Preference.PreferenceItem.TrackerPreference {
        return Preference.PreferenceItem.TrackerPreference(
            tracker = service,
            title = serviceTitle,
            login = {
                Logger.i(LOG_TAG) { "credentials-login $serviceTitle" }
                scope.launch {
                    val creds = dialogHostState.awaitTrackLogin(
                        serviceTitle = serviceTitle,
                        usernameLabel = usernameLabel,
                        initialUsername = service.getUsername(),
                        initialPassword = service.getPassword(),
                    ) ?: return@launch
                    try {
                        val ok = withIOContext { service.login(creds.username, creds.password) }
                        context.toast(
                            if (ok) MR.strings.successfully_logged_in else MR.strings.unknown_error,
                        )
                    } catch (e: Exception) {
                        context.toast(e.message ?: context.getString(MR.strings.unknown_error))
                    }
                }
            },
            logout = {
                Logger.i(LOG_TAG) { "logout-clicked $serviceTitle" }
                scope.launch { confirmAndLogout(service, serviceTitle, context, dialogHostState) }
            },
        )
    }

    private fun enhancedTracker(
        service: EnhancedTrackService,
        trackService: TrackService,
        serviceTitle: String,
    ): Preference.PreferenceItem.TrackerPreference {
        return Preference.PreferenceItem.TrackerPreference(
            tracker = trackService,
            title = serviceTitle,
            login = {
                Logger.i(LOG_TAG) { "enhanced-login $serviceTitle" }
                service.loginNoop()
            },
            logout = {
                Logger.i(LOG_TAG) { "enhanced-logout $serviceTitle" }
                trackService.logout()
            },
        )
    }

    private suspend fun confirmAndLogout(
        service: TrackService,
        serviceTitle: String,
        context: android.content.Context,
        dialogHostState: DialogHostState,
    ) {
        var confirmed = false
        dialogHostState.simple {
            title = context.getString(MR.strings.log_out_from_, serviceTitle)
            confirmTextRes = MR.strings.log_out
            onConfirm = { confirmed = true }
        }
        if (confirmed) {
            service.logout()
            context.toast(MR.strings.successfully_logged_out)
        }
    }
}
