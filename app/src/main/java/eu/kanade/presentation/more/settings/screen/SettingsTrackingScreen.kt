package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
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
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.hikka.HikkaApi
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBakaApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdates
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdatesListMapping
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.ui.setting.track.TrackerWebViewLoginActivity
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import exh.md.utils.MdConstants
import exh.md.utils.MdUtil
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

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
                imageVector = MaterialSymbols.AutoMirroredRounded.Help,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val trackPreferences = remember { context.appGraph.trackPreferences }
        val reikaiLibraryPreferences = remember { context.appGraph.reikaiLibraryPreferences } // RK
        val trackerManager = remember { context.appGraph.trackerManager }
        val sourceManager = remember { context.appGraph.sourceManager }
        // RK: RanobeDB's sync toggle only means anything once it is bound, so it follows it.
        val ranobeDbLoggedIn by trackerManager.ranobeDb.isLoggedInFlow
            .collectAsState(initial = trackerManager.ranobeDb.isLoggedIn)
        val novelListLoggedIn by trackerManager.novelList.isLoggedInFlow
            .collectAsState(initial = trackerManager.novelList.isLoggedIn)
        val novelUpdatesLoggedIn by trackerManager.novelUpdates.isLoggedInFlow
            .collectAsState(initial = trackerManager.novelUpdates.isLoggedIn)

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
                // RK --> single-secret login, for a tracker whose whole credential is a pasted token
                is TokenLoginDialog -> {
                    TrackingTokenLoginDialog(
                        tracker = tracker,
                        tokenStringRes = tokenStringRes,
                        helpStringRes = helpStringRes,
                        helpUrl = helpUrl,
                        // Offered only where the service accepts a session cookie as well.
                        onWebViewLogin = (tracker as? CookieLoginTracker)?.let {
                            {
                                context.startActivity(
                                    TrackerWebViewLoginActivity.newIntent(context, tracker.id),
                                )
                                dialog = null
                            }
                        },
                        onDismissRequest = { dialog = null },
                    )
                }
                is NovelUpdatesListMappingDialog -> {
                    NovelUpdatesListMappingDialogContent(
                        tracker = trackerManager.novelUpdates,
                        preference = trackPreferences.novelUpdatesCustomListMapping,
                        onDismissRequest = { dialog = null },
                    )
                }
                // RK <--
                is LogoutDialog -> {
                    TrackingLogoutDialog(
                        tracker = tracker,
                        onDismissRequest = { dialog = null },
                    )
                }
            }
        }

        val installedSources by produceState(initialValue = emptyList()) { value = sourceManager.getAll() }
        val enhancedTrackers = trackerManager.trackers
            .filter { it is EnhancedTracker }
            .partition { service ->
                val acceptedSources = (service as EnhancedTracker).getAcceptedSources()
                installedSources.any { it::class.qualifiedName in acceptedSources }
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
                    // RK: RanobeDB light-novel tracker. The whole credential is a personal access
                    // token generated on the site, so no OAuth redirect and no WebView.
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.ranobeDb,
                        login = {
                            dialog = TokenLoginDialog(
                                tracker = trackerManager.ranobeDb,
                                tokenStringRes = MR.strings.login_token,
                                helpStringRes = MR.strings.login_ranobedb_token_info,
                                helpUrl = RANOBEDB_LOGIN_URL,
                            )
                        },
                        logout = { dialog = LogoutDialog(trackerManager.ranobeDb) },
                    ),
                    // RK: NovelList light-novel tracker. It issues no personal token, so signing in
                    // goes straight to the browser rather than offering a field nobody can fill.
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.novelList,
                        login = {
                            context.startActivity(
                                TrackerWebViewLoginActivity.newIntent(context, trackerManager.novelList.id),
                            )
                        },
                        logout = { dialog = LogoutDialog(trackerManager.novelList) },
                    ),
                    // RK: NovelUpdates. Scraped, so the browser sign-in is the only way in.
                    Preference.PreferenceItem.TrackerPreference(
                        tracker = trackerManager.novelUpdates,
                        login = {
                            context.startActivity(
                                TrackerWebViewLoginActivity.newIntent(context, trackerManager.novelUpdates.id),
                            )
                        },
                        logout = { dialog = LogoutDialog(trackerManager.novelUpdates) },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.tracking_info)),
                ) + ranobeDbPreferences(trackPreferences, ranobeDbLoggedIn) +
                    novelListPreferences(trackPreferences, novelListLoggedIn) +
                    novelUpdatesPreferences(trackPreferences, novelUpdatesLoggedIn) { dialog = it },
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
                            imageVector = MaterialSymbols.Rounded.Close,
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
                                        MaterialSymbols.Rounded.Visibility
                                    } else {
                                        MaterialSymbols.Rounded.VisibilityOff
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

    // RK --> single-secret token login, plus RanobeDB's two sync toggles.
    //
    // The sign-in page, not the token form: RanobeDB's settings tabs are a `?view=` query rather
    // than a path, and the whole page renders empty until you are signed in, so sending a signed-out
    // user straight there shows them nothing. `login_ranobedb_token_info` carries the last step.
    private const val RANOBEDB_LOGIN_URL = "https://ranobedb.org/login"

    @Composable
    private fun ranobeDbPreferences(
        trackPreferences: TrackPreferences,
        isLoggedIn: Boolean,
    ): List<Preference.PreferenceItem<out Any, out Any>> {
        if (!isLoggedIn) return emptyList()
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = trackPreferences.ranobeDbSyncWhileReading,
                title = stringResource(MR.strings.pref_ranobedb_sync_while_reading),
                subtitle = stringResource(MR.strings.pref_ranobedb_sync_while_reading_summary),
            ),
        )
    }

    /**
     * NovelList answers on a generated hosting URL rather than a domain it owns, so the address is
     * editable and a move does not need an app update. Blank restores the built-in default.
     */
    @Composable
    private fun novelListPreferences(
        trackPreferences: TrackPreferences,
        isLoggedIn: Boolean,
    ): List<Preference.PreferenceItem<out Any, out Any>> {
        if (!isLoggedIn) return emptyList()
        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                preference = trackPreferences.novelListApiUrl,
                title = stringResource(MR.strings.pref_novellist_api_url),
                subtitle = stringResource(MR.strings.pref_novellist_api_url_summary),
            ),
        )
    }

    /**
     * NovelUpdates lets a user rename and add reading lists, so which list a status moves an entry
     * to is theirs to choose. The picker only appears once the mapping is switched on.
     */
    @Composable
    private fun novelUpdatesPreferences(
        trackPreferences: TrackPreferences,
        isLoggedIn: Boolean,
        onShowDialog: (Any) -> Unit,
    ): List<Preference.PreferenceItem<out Any, out Any>> {
        if (!isLoggedIn) return emptyList()
        val useCustom by trackPreferences.novelUpdatesUseCustomListMapping.collectPreferenceAsState()
        return listOfNotNull(
            Preference.PreferenceItem.SwitchPreference(
                preference = trackPreferences.novelUpdatesUseCustomListMapping,
                title = stringResource(MR.strings.pref_novelupdates_custom_lists),
                subtitle = stringResource(MR.strings.pref_novelupdates_custom_lists_summary),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_novelupdates_configure_lists),
                subtitle = stringResource(MR.strings.pref_novelupdates_configure_lists_summary),
                onClick = { onShowDialog(NovelUpdatesListMappingDialog) },
            ).takeIf { useCustom },
        )
    }

    @Composable
    private fun TrackingTokenLoginDialog(
        tracker: Tracker,
        tokenStringRes: StringResource,
        helpStringRes: StringResource,
        helpUrl: String,
        onWebViewLogin: (() -> Unit)?,
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()

        var token by remember { mutableStateOf(TextFieldValue(tracker.getPassword())) }
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
                            imageVector = MaterialSymbols.Rounded.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onWebViewLogin != null) {
                        // The easier path, so it leads. A token outlasts a session cookie, which is
                        // why the field below stays rather than being replaced by this.
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onWebViewLogin,
                        ) {
                            Text(text = stringResource(MR.strings.login_with_browser))
                        }
                        Text(text = stringResource(MR.strings.login_token_or_paste))
                    } else {
                        Text(text = stringResource(MR.strings.login_token_info))
                    }

                    Text(
                        text = stringResource(helpStringRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    var hideToken by remember { mutableStateOf(true) }
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.Password },
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(text = stringResource(tokenStringRes)) },
                        trailingIcon = {
                            IconButton(onClick = { hideToken = !hideToken }) {
                                Icon(
                                    imageVector = if (hideToken) {
                                        MaterialSymbols.Rounded.Visibility
                                    } else {
                                        MaterialSymbols.Rounded.VisibilityOff
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        visualTransformation = if (hideToken) {
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

                    TextButton(onClick = { uriHandler.openUri(helpUrl) }) {
                        Text(text = stringResource(MR.strings.login_get_token))
                    }
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !processing && token.text.isNotBlank(),
                    onClick = {
                        scope.launchIO {
                            processing = true
                            // The tracker reads the token out of the password slot, so the username
                            // it stores comes from the service itself once the token validates.
                            val result = checkLogin(
                                context = context,
                                tracker = tracker,
                                username = "",
                                password = token.text,
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
    // RK <--

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

// RK: the single-secret counterpart to LoginDialog, for a pasted token with no username.
// [helpStringRes] says where on that service a token is generated, which no generic line can.
private data class TokenLoginDialog(
    val tracker: Tracker,
    val tokenStringRes: StringResource,
    val helpStringRes: StringResource,
    val helpUrl: String,
)

private data class LogoutDialog(
    val tracker: Tracker,
)

// RK: NovelUpdates has one tracker, so the mapping picker needs no argument to identify it.
private object NovelUpdatesListMappingDialog

/**
 * Picks which of the user's NovelUpdates lists each status moves an entry to. The lists are fetched
 * on open rather than cached, so a list renamed on the site shows its current name; a fetch failure
 * leaves the existing choices editable rather than emptying them.
 */
@Composable
private fun NovelUpdatesListMappingDialogContent(
    tracker: NovelUpdates,
    preference: tachiyomi.core.common.preference.Preference<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val json = remember { context.appGraph.json }

    var lists by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var chosen by remember {
        mutableStateOf(NovelUpdatesListMapping.from(preference.get(), json).asStatusToList())
    }

    var loading by remember { mutableStateOf(true) }
    var openFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        runCatching { tracker.readingLists() }
            .onSuccess { lists = it }
            .onFailure { withUIContext { context.toast(MR.strings.pref_novelupdates_lists_failed) } }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.pref_novelupdates_configure_lists)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (loading) {
                            stringResource(MR.strings.pref_novelupdates_lists_loading)
                        } else {
                            stringResource(MR.strings.pref_novelupdates_lists_loaded, lists.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launchIO {
                                val fetched = runCatching { tracker.readingLists() }
                                withUIContext {
                                    fetched
                                        .onSuccess { lists = it }
                                        .onFailure { context.toast(MR.strings.pref_novelupdates_lists_failed) }
                                    loading = false
                                }
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_webview_refresh))
                    }
                }
                HorizontalDivider()
                NovelUpdates.STATUSES.forEach { status ->
                    val current = chosen[status]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = tracker.getStatus(status)?.let { stringResource(it) }.orEmpty())
                        Box {
                            OutlinedButton(
                                enabled = lists.isNotEmpty(),
                                onClick = { openFor = status },
                            ) {
                                Text(
                                    text = lists.firstOrNull { it.first == current.toString() }?.second
                                        ?: current.toString(),
                                    maxLines = 1,
                                )
                            }
                            DropdownMenu(
                                expanded = openFor == status,
                                onDismissRequest = { openFor = null },
                            ) {
                                lists.forEach { (listId, listName) ->
                                    RadioMenuItem(
                                        text = { Text(text = listName) },
                                        isChecked = listId == current.toString(),
                                        onClick = {
                                            listId.toLongOrNull()?.let { chosen = chosen + (status to it) }
                                            openFor = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launchIO {
                            preference.set(json.encodeToString(chosen.mapKeys { it.key.toString() }))
                            withUIContext { onDismissRequest() }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            }
        },
    )
}
