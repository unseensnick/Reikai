package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.hikka.HikkaApi
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBakaApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import exh.md.utils.MdConstants
import exh.md.utils.MdUtil
import mihon.app.di.appGraph
import reikai.domain.recommendation.taste.ObservedTag
import reikai.domain.recommendation.taste.isBuiltInSexualTag
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsTrackingScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_tracking

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        // RK: Reikai's docs
        IconButton(onClick = { uriHandler.openUri("${Constants.URL_DOCS}/guides/tracking") }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val trackPreferences = remember { context.appGraph.trackPreferences }
        val reikaiLibraryPreferences = remember { context.appGraph.reikaiLibraryPreferences } // RK
        val tasteLibraryRepository = remember { context.appGraph.tasteLibraryRepository } // RK
        val getObservedTasteTags = remember { context.appGraph.getObservedTasteTags } // RK
        val trackerManager = remember { context.appGraph.trackerManager }
        val sourceManager = remember { context.appGraph.sourceManager }

        // RK: the two adult-tag pickers offer only real tags from the cache, split by whether the
        // built-in list already catches them, so each list holds only choices that change something.
        // withIOContext because a produceState body runs on the composition's Main dispatcher, and this
        // reads the whole taste cache and splits every row's tag string.
        val observedTags by produceState(emptyList<ObservedTag>()) {
            value = withIOContext { getObservedTasteTags.await() }
        }
        val (caughtTags, uncaughtTags) = remember(observedTags) {
            observedTags.partition { isBuiltInSexualTag(it.tag) }
        }
        // A pick outlives the cache row it came from, and still screens carousel candidates and
        // Fill-from-tracker genres, neither of which reads the cache. Offering it back keeps it
        // removable after a pull is turned off or the cache is dropped.
        val alwaysPicked by trackPreferences.alwaysAdultTags.collectAsState()
        val neverPicked by trackPreferences.neverAdultTags.collectAsState()
        val alwaysEntries = remember(uncaughtTags, alwaysPicked) { adultTagEntries(uncaughtTags, alwaysPicked) }
        val neverEntries = remember(caughtTags, neverPicked) { adultTagEntries(caughtTags, neverPicked) }

        var dialog by remember { mutableStateOf<Any?>(null) }
        dialog?.run {
            when (this) {
                is LoginDialog -> {
                    TrackingLoginDialog(
                        tracker = tracker,
                        uNameStringRes = uNameStringRes,
                        onDismissRequest = { dialog = null },
                    )
                }
                is LogoutDialog -> {
                    TrackingLogoutDialog(
                        tracker = tracker,
                        onDismissRequest = { dialog = null },
                    )
                }
            }
        }

        val enhancedTrackers = trackerManager.trackers
            .filter { it is EnhancedTracker }
            .partition { service ->
                val acceptedSources = (service as EnhancedTracker).getAcceptedSources()
                sourceManager.getAll().any { it::class.qualifiedName in acceptedSources }
            }
        var enhancedTrackerInfo = stringResource(MR.strings.enhanced_tracking_info)
        if (enhancedTrackers.second.isNotEmpty()) {
            val missingSourcesInfo = stringResource(
                MR.strings.enhanced_services_not_installed,
                enhancedTrackers.second.joinToString { it.name },
            )
            enhancedTrackerInfo += "\n\n$missingSourcesInfo"
        }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = trackPreferences.autoUpdateTrack,
                title = stringResource(MR.strings.pref_auto_update_manga_sync),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = trackPreferences.autoUpdateTrackOnMarkRead,
                entries = AutoTrackState.entries
                    .associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_auto_update_manga_on_mark_read),
            ),
            // RK: share a tracker added to one source across the rest of a merged group
            Preference.PreferenceItem.SwitchPreference(
                preference = reikaiLibraryPreferences.syncTrackerLinksGrouped,
                title = stringResource(MR.strings.pref_sync_tracker_links_grouped),
                subtitle = stringResource(MR.strings.pref_sync_tracker_links_grouped_summary),
            ),
            // RK: sexual content only, so it never hides a series for being violent or dark
            Preference.PreferenceItem.SwitchPreference(
                preference = trackPreferences.showAdultTrackerContent,
                title = stringResource(MR.strings.pref_show_adult_tracker_content),
                subtitle = stringResource(MR.strings.pref_show_adult_tracker_content_summary),
                // MyAnimeList's library pull asks for adult entries only when this is on, so either
                // way the cache was built under the old answer: drop it and let the next pull
                // rebuild. Turning adult content back off, this is also what gets those rows off
                // disk, which filtering at read time alone would leave there.
                // withIOContext because onValueChanged runs on rememberCoroutineScope, which is Main.
                onValueChanged = {
                    withIOContext { tasteLibraryRepository.deleteAll() }
                    true
                },
            ),
            // RK: enabled means visible here, so each picker shows while it has anything to offer and
            // hides only when it has nothing at all. Labels carry the entry count where the cache knows it.
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = trackPreferences.alwaysAdultTags,
                entries = alwaysEntries,
                title = stringResource(MR.strings.pref_always_adult_tags),
                subtitle = stringResource(MR.strings.pref_adult_tags_summary),
                enabled = alwaysEntries.isNotEmpty(),
            ),
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = trackPreferences.neverAdultTags,
                entries = neverEntries,
                title = stringResource(MR.strings.pref_never_adult_tags),
                subtitle = stringResource(MR.strings.pref_adult_tags_summary),
                enabled = neverEntries.isNotEmpty(),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.services),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.myAnimeList,
                        login = { context.openInBrowser(MyAnimeListApi.authUrl(), forceDefaultBrowser = true) },
                        logout = { dialog = LogoutDialog(trackerManager.myAnimeList) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.aniList,
                        login = { context.openInBrowser(AnilistApi.authUrl(), forceDefaultBrowser = true) },
                        logout = { dialog = LogoutDialog(trackerManager.aniList) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.kitsu,
                        login = { dialog = LoginDialog(trackerManager.kitsu, MR.strings.email) },
                        logout = { dialog = LogoutDialog(trackerManager.kitsu) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.mangaUpdates,
                        login = { dialog = LoginDialog(trackerManager.mangaUpdates, MR.strings.username) },
                        logout = { dialog = LogoutDialog(trackerManager.mangaUpdates) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.shikimori,
                        login = { context.openInBrowser(ShikimoriApi.authUrl(), forceDefaultBrowser = true) },
                        logout = { dialog = LogoutDialog(trackerManager.shikimori) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.bangumi,
                        login = { context.openInBrowser(BangumiApi.authUrl(), forceDefaultBrowser = true) },
                        logout = { dialog = LogoutDialog(trackerManager.bangumi) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.hikka,
                        login = { context.openInBrowser(HikkaApi.authUrl(), forceDefaultBrowser = true) },
                        logout = { dialog = LogoutDialog(trackerManager.hikka) },
                    ),
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.mangaBaka,
                        login = { context.openInBrowser(MangaBakaApi.authUrl(), forceDefaultBrowser = true) },
                        logout = { dialog = LogoutDialog(trackerManager.mangaBaka) },
                    ),
                    // RK: MangaDex MDList tracker. Browser OAuth (PKCE), callback lands in
                    // MangaDexLoginActivity; needs the MangaDex source installed + enabled to bind.
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.mdList,
                        login = {
                            context.openInBrowser(
                                MdConstants.Login.authUrl(MdUtil.getPkceChallengeCode()),
                                forceDefaultBrowser = true,
                            )
                        },
                        logout = { dialog = LogoutDialog(trackerManager.mdList) },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.tracking_info)),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.enhanced_services),
                preferenceItems = (
                    enhancedTrackers.first
                        .map { service ->
                            Preference.PreferenceItem.TrackerPreference(
                                tracker = service,
                                login = { (service as EnhancedTracker).loginNoop() },
                                logout = service::logout,
                            )
                        } + listOf(Preference.PreferenceItem.InfoPreference(enhancedTrackerInfo))
                    ),
            ),
        )
    }

    @Composable
    private fun TrackingLoginDialog(
        tracker: Tracker,
        uNameStringRes: StringResource,
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var username by remember { mutableStateOf(TextFieldValue(tracker.getUsername())) }
        var password by remember { mutableStateOf(TextFieldValue(tracker.getPassword())) }
        var processing by remember { mutableStateOf(false) }
        var inputError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(MR.strings.login_title, tracker.name),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(text = stringResource(uNameStringRes)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                        isError = inputError && !processing,
                    )

                    var hidePassword by remember { mutableStateOf(true) }
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(text = stringResource(MR.strings.password)) },
                        trailingIcon = {
                            IconButton(onClick = { hidePassword = !hidePassword }) {
                                Icon(
                                    imageVector = if (hidePassword) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        visualTransformation = if (hidePassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        isError = inputError && !processing,
                    )
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !processing && username.text.isNotBlank() && password.text.isNotBlank(),
                    onClick = {
                        scope.launchIO {
                            processing = true
                            val result = checkLogin(
                                context = context,
                                tracker = tracker,
                                username = username.text,
                                password = password.text,
                            )
                            inputError = !result
                            if (result) onDismissRequest()
                            processing = false
                        }
                    },
                ) {
                    val id = if (processing) MR.strings.logging_in else MR.strings.login
                    Text(text = stringResource(id))
                }
            },
        )
    }

    private suspend fun checkLogin(
        context: Context,
        tracker: Tracker,
        username: String,
        password: String,
    ): Boolean {
        return try {
            tracker.login(username, password)
            withUIContext { context.toast(MR.strings.login_success) }
            true
        } catch (e: Throwable) {
            tracker.logout()
            withUIContext { context.toast(e.message.toString()) }
            false
        }
    }

    @Composable
    private fun TrackingLogoutDialog(
        tracker: Tracker,
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(
                    text = stringResource(MR.strings.logout_title, tracker.name),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDismissRequest,
                    ) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            tracker.logout()
                            onDismissRequest()
                            context.toast(MR.strings.logout_success)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(text = stringResource(MR.strings.logout))
                    }
                }
            },
        )
    }
}

private data class LoginDialog(
    val tracker: Tracker,
    val uNameStringRes: StringResource,
)

private data class LogoutDialog(
    val tracker: Tracker,
)

/** RK: what an adult-tag picker offers. Cache tags carry their entry count; a tag the user already
 *  picked is offered back even once the cache no longer lists it, so it stays removable. */
private fun adultTagEntries(observed: List<ObservedTag>, picked: Set<String>): Map<String, String> {
    val fromCache = observed.associate { it.tag to "${it.tag} (${it.count})" }
    return fromCache + (picked - fromCache.keys).associateWith { it }
}
