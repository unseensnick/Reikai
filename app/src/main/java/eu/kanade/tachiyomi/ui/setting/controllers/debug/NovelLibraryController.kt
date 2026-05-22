package eu.kanade.tachiyomi.ui.setting.controllers.debug

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.library.NovelLibraryScreen

class NovelLibraryController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        NovelLibraryScreen()
    }

    companion object {
        const val title = "LN library"
    }
}
