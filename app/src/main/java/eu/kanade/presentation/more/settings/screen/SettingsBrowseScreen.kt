package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        val getExtensionStoreCountAsFlow = remember { context.appGraph.getExtensionStoreCountAsFlow }
        // RK: the Repos screen is unified (manga + light novel), so count both.
        val novelPreferences = remember { context.appGraph.novelPreferences }
        // RK --> the Feed tab's own switches, which the rest of its group hangs off
        val reikaiSourcePreferences = remember { context.appGraph.reikaiSourcePreferences }
        val showFeedTab by reikaiSourcePreferences.showFeedTab.changes()
            .collectAsState(reikaiSourcePreferences.showFeedTab.get())
        // RK <--

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)
        val novelRepoUrls by novelPreferences.addedRepoUrls().changes()
            .collectAsState(novelPreferences.addedRepoUrls().get())

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        // RK: show manga + light-novel repo counts (the Repos screen holds both).
                        subtitle = stringResource(
                            MR.strings.extension_repos_subtitle,
                            reposCount.toInt(),
                            novelRepoUrls.size,
                        ),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            // RK --> the Feed tab is Reikai's, and every switch here is off by default
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_feed),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = reikaiSourcePreferences.showFeedTab,
                        title = stringResource(MR.strings.pref_show_feed_tab),
                        subtitle = stringResource(MR.strings.pref_show_feed_tab_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = reikaiSourcePreferences.feedTabInFront,
                        title = stringResource(MR.strings.pref_feed_tab_first),
                        subtitle = stringResource(MR.strings.pref_feed_tab_first_summary),
                        enabled = showFeedTab,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = reikaiSourcePreferences.hideInLibraryFeedItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                        enabled = showFeedTab,
                    ),
                ),
            ),
            // RK <--
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource,
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.parental_controls_info)),
                ),
            ),
        )
    }
}
