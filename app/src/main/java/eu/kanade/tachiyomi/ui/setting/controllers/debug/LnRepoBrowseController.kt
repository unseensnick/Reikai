package eu.kanade.tachiyomi.ui.setting.controllers.debug

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.repo.LnRepoBrowseScreen

class LnRepoBrowseController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        LnRepoBrowseScreen()
    }

    companion object {
        const val title = "LN plugin repo browse"
    }
}
