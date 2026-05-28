package yokai.presentation.settings.screen

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.system.SideNavMode
import eu.kanade.tachiyomi.util.system.appDelegateNightMode
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.setAppIcon
import kotlin.math.max
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.simple
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.widget.ThemeTilePicker
import yokai.presentation.util.addBetaTag

/**
 * Appearance settings on the Compose + Voyager stack. The legacy Conductor screen lives at
 * [eu.kanade.tachiyomi.ui.setting.controllers.legacy.SettingsAppearanceLegacyController] and stays
 * reachable through a long-press on the row (mirrors the Security / Advanced flip pattern in
 * `.claude/rules/architecture.md`).
 *
 * UX delta from the legacy screen: the "Follow system theme" switch is promoted to a three-way
 * night-mode list (Follow system / Always light / Always dark), so the user can force either
 * mode without flipping the device-level preference. The pure-black-dark toggle stays
 * conditional on the active mode allowing dark.
 *
 * Theme tile previews are out of scope for this slice; the visual picker is a follow-up (the
 * legacy widget hosted a custom RecyclerView that doesn't port directly). For now the picker is
 * two list rows (light + dark) with the existing Themes-enum entries.
 */
object SettingsAppearanceScreen : ComposableSettings() {

    private fun readResolve() = SettingsAppearanceScreen

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.appearance

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences: PreferencesHelper by injectLazy()
        val basePreferences: BasePreferences by injectLazy()
        return persistentListOf(
            getAppThemeGroup(preferences, basePreferences),
            getDetailsPageGroup(preferences),
            getNavigationGroup(preferences),
        )
    }

    // ---------- App theme ----------

    @Composable
    private fun getAppThemeGroup(
        preferences: PreferencesHelper,
        basePreferences: BasePreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val alertDialog = LocalDialogHostState.currentOrThrow
        val nightMode by preferences.nightMode().collectAsState()
        val followSystem = nightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        // The pure-black-dark switch only makes sense when dark is reachable: either device is
        // on follow-system (and may be dark) or the user explicitly forced dark. The dark-theme
        // picker itself stays visible regardless so the user can pre-pick a dark variant while
        // the app is rendering light — matches the legacy widget which always shows both rails.
        val darkReachable = nightMode != AppCompatDelegate.MODE_NIGHT_NO

        val followSystemTitle = stringResource(MR.strings.follow_system_theme)

        val appIconEntries = BasePreferences.AppIcons.entries
            .associateWith { it.displayName }
            .toImmutableMap()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.app_theme),
            // Order matches the legacy SettingsAppearanceLegacyController exactly: theme tile
            // rails (light then dark), then app icon, then follow-system switch, then pure-black
            // switch. The tile picker writes the pref + triggers recreate internally on tap.
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.light_theme),
                    ) { _ ->
                        ThemeTilePicker(pref = preferences.lightTheme(), isDark = false)
                    },
                )
                // Always shown. Tapping a light tile while in forced-dark flips nightMode to
                // forced-light (legacy ThemeTilePicker behavior) which would hide this row if it
                // was gated on darkReachable, locking the user out of the dark picker until
                // they reopened the legacy menu. Keep it visible so the user can always change
                // which dark theme will be active when the app does run dark.
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.dark_theme),
                    ) { _ ->
                        ThemeTilePicker(pref = preferences.darkTheme(), isDark = true)
                    },
                )
                // App icon: experimental. Title carries the same BETA superscript tag the legacy
                // controller uses via [addBetaTag]. Selection routes through a confirmation
                // dialog before calling [setAppIcon] (which toggles activity-alias enabled state
                // via PackageManager.setComponentEnabledSetting and writes the pref). Returning
                // false from onValueChanged suppresses the framework's auto-set so setAppIcon
                // owns persistence; if the user cancels the dialog the pref stays untouched.
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = basePreferences.appIcon(),
                        title = "Change App Icon",
                        titleAnnotated = "Change App Icon".addBetaTag(),
                        subtitle = "This feature is still very experimental",
                        entries = appIconEntries,
                        onValueChanged = { newIcon ->
                            scope.launch {
                                alertDialog.simple {
                                    title = "Change App Icon?"
                                    text = "[Very Experimental] This may kill the app!"
                                    onConfirm = {
                                        context.setAppIcon(basePreferences, newIcon)
                                    }
                                }
                            }
                            false
                        },
                    ),
                )
                // Follow-system row matches the legacy switch shape exactly: OFF locks night mode
                // to whatever is currently rendered, ON sets MODE_NIGHT_FOLLOW_SYSTEM. Wrapping
                // SwitchPreferenceWidget in CustomPreference because the backing store is the Int
                // nightMode pref, which doesn't fit SwitchPreference's PreferenceData<Boolean>.
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = followSystemTitle,
                    ) { _ ->
                        SwitchPreferenceWidget(
                            title = followSystemTitle,
                            checked = followSystem,
                            onCheckedChanged = { checked ->
                                preferences.nightMode().set(
                                    if (checked) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                    else context.appDelegateNightMode(),
                                )
                                (context as? Activity)?.recreate()
                            },
                        )
                    },
                )
                if (darkReachable) {
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            pref = preferences.themeDarkAmoled(),
                            title = stringResource(MR.strings.pure_black_dark_mode),
                            // Amoled flips the background of every dark theme to true black;
                            // recreate so the active theme picks up the new background color.
                            onValueChanged = { (context as? Activity)?.recreate(); true },
                        ),
                    )
                }
            }.toPersistentList(),
        )
    }

    // ---------- Details page ----------

    @Composable
    private fun getDetailsPageGroup(preferences: PreferencesHelper): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.details_page),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.themeMangaDetails(),
                    title = stringResource(MR.strings.theme_buttons_based_on_cover),
                ),
            ),
        )

    // ---------- Navigation ----------

    @Composable
    private fun getNavigationGroup(preferences: PreferencesHelper): Preference.PreferenceGroup {
        val context = LocalContext.current
        // The side-nav icon alignment row only applies on tablets (>= 720dp shortest edge); the
        // option doesn't exist on phones because there's no side nav rail.
        val showSideNavAlignment = remember {
            max(
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels,
            ) >= 720.dpToPx
        }

        val sideNavAlignmentEntries = remember {
            persistentListOf(
                0 to MR.strings.top,
                1 to MR.strings.center,
                2 to MR.strings.bottom,
            )
        }.associate { (value, res) -> value to stringResource(res) }.toImmutableMap()

        val sideNavModeEntries = SideNavMode.entries
            .associate { it.prefValue to stringResource(it.stringRes) }
            .toImmutableMap()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.navigation),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        pref = preferences.hideBottomNavOnScroll(),
                        title = stringResource(MR.strings.hide_bottom_nav),
                        subtitle = stringResource(MR.strings.hides_on_scroll),
                    ),
                )
                if (showSideNavAlignment) {
                    add(
                        Preference.PreferenceItem.ListPreference(
                            pref = preferences.sideNavIconAlignment(),
                            title = stringResource(MR.strings.side_nav_icon_alignment),
                            entries = sideNavAlignmentEntries,
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = preferences.sideNavMode(),
                        title = stringResource(MR.strings.use_side_navigation),
                        entries = sideNavModeEntries,
                        // Side-nav-mode flips swap the navigation host (bottom vs side), which
                        // can only land via activity recreate. Matches the legacy `onChange`.
                        onValueChanged = { (context as? Activity)?.recreate(); true },
                    ),
                )
                add(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.by_default_side_nav_info),
                    ),
                )
            }.toPersistentList(),
        )
    }
}
