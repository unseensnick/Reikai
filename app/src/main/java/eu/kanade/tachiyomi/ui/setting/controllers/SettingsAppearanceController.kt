package eu.kanade.tachiyomi.ui.setting.controllers

import eu.kanade.tachiyomi.ui.setting.SettingsComposeController
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.screen.SettingsAppearanceScreen

/**
 * Compose-default Appearance settings. The legacy Conductor screen lives at
 * [eu.kanade.tachiyomi.ui.setting.controllers.legacy.SettingsAppearanceLegacyController] and stays
 * reachable through a long-press on the Settings row plus indexing in
 * [eu.kanade.tachiyomi.ui.setting.controllers.search.SettingsSearchHelper].
 */
class SettingsAppearanceController : SettingsComposeController() {
    override fun getComposableSettings(): ComposableSettings = SettingsAppearanceScreen
}
