package eu.kanade.tachiyomi.ui.setting.controllers.debug

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.track.NovelTrackProbeScreen

class NovelTrackProbeController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        NovelTrackProbeScreen()
    }

    companion object {
        const val title = "LN track probe (AniList)"
    }
}
