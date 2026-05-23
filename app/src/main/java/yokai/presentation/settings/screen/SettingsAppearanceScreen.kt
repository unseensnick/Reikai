package yokai.presentation.settings.screen

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.system.SideNavMode
import eu.kanade.tachiyomi.util.system.Themes
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlin.math.max
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import uy.kohesive.injekt.injectLazy
import yokai.domain.base.BasePreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings

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
            getToolbarGroup(preferences),
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
        val nightMode by preferences.nightMode().collectAsState()
        // The pure-black-dark toggle only makes sense when dark is reachable: either the device
        // is on follow-system (and may be dark) or the user explicitly forced dark.
        val darkReachable = nightMode != AppCompatDelegate.MODE_NIGHT_NO

        // Night-mode labels: "Light" has no MR string entry yet so the literal stands until a
        // localised resource lands; "Dark" already exists in strings.xml.
        val nightModeEntries = persistentListOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to stringResource(MR.strings.follow_system_theme),
            AppCompatDelegate.MODE_NIGHT_NO to "Light",
            AppCompatDelegate.MODE_NIGHT_YES to stringResource(MR.strings.dark),
        ).toMap().toImmutableMap()

        // Themes enum carries day + night-variant strings via nameRes / darkNameRes so the light
        // and dark pickers show appropriate labels for each theme.
        val lightThemeEntries = Themes.entries
            .associateWith { stringResource(it.nameRes) }
            .toImmutableMap()
        val darkThemeEntries = Themes.entries
            .associateWith { stringResource(it.darkNameRes) }
            .toImmutableMap()

        val appIconEntries = BasePreferences.AppIcons.entries
            .associateWith { it.displayName }
            .toImmutableMap()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.app_theme),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = preferences.nightMode(),
                        title = stringResource(MR.strings.follow_system_theme),
                        entries = nightModeEntries,
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = preferences.lightTheme(),
                        title = stringResource(MR.strings.light_theme),
                        entries = lightThemeEntries,
                    ),
                )
                if (darkReachable) {
                    add(
                        Preference.PreferenceItem.ListPreference(
                            pref = preferences.darkTheme(),
                            title = stringResource(MR.strings.dark_theme),
                            entries = darkThemeEntries,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            pref = preferences.themeDarkAmoled(),
                            title = stringResource(MR.strings.pure_black_dark_mode),
                        ),
                    )
                }
                // App icon: experimental, beta-tagged in the title until the change-app-icon flow
                // is reliable across OEMs.
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = basePreferences.appIcon(),
                        title = "Change App Icon (Beta)",
                        subtitle = "This feature is still very experimental",
                        entries = appIconEntries,
                    ),
                )
            }.toPersistentList(),
        )
    }

    // ---------- Toolbar ----------

    @Composable
    private fun getToolbarGroup(preferences: PreferencesHelper): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = "Toolbar",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.useLargeToolbar(),
                    title = stringResource(MR.strings.expanded_toolbar),
                    subtitle = stringResource(MR.strings.show_larger_toolbar),
                ),
            ),
        )

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
