package eu.kanade.tachiyomi.ui.setting.controllers.debug

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.probe.LnPluginHostProbeScreen

class LnPluginHostProbeController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        LnPluginHostProbeScreen()
    }

    companion object {
        const val title = "LN plugin host probe"
    }
}
