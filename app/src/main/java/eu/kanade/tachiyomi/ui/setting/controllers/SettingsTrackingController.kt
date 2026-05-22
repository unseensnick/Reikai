package eu.kanade.tachiyomi.ui.setting.controllers

import eu.kanade.tachiyomi.ui.setting.SettingsComposeController
import yokai.presentation.settings.ComposableSettings
import yokai.presentation.settings.screen.SettingsTrackingScreen

class SettingsTrackingController : SettingsComposeController() {
    override fun getComposableSettings(): ComposableSettings = SettingsTrackingScreen
}
