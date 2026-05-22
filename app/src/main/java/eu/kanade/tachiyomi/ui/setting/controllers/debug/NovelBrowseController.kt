package eu.kanade.tachiyomi.ui.setting.controllers.debug

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.browse.NovelBrowseScreen

class NovelBrowseController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        NovelBrowseScreen()
    }

    companion object {
        const val title = "LN browse"
    }
}
