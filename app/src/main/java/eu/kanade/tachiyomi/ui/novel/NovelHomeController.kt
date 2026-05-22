package eu.kanade.tachiyomi.ui.novel

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.home.NovelHomeScreen

/**
 * Top-level Novels tab controller. Hosts [NovelHomeScreen] as the bottom-nav destination for
 * light-novel content. Not under `ui/setting/controllers/debug/` because this is production UI,
 * not a debug entry; the debug entries still exist alongside under Settings → Light novels (Beta).
 */
class NovelHomeController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        NovelHomeScreen()
    }
}
