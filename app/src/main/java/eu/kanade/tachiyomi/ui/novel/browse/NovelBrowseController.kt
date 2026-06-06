package eu.kanade.tachiyomi.ui.novel.browse

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.novel.NovelDetailsController
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.presentation.novel.browse.NovelBrowseScreen

class NovelBrowseController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    constructor(sourceId: String) : this(
        Bundle().apply { putString(ARG_SOURCE_ID, sourceId) },
    )

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = NovelBrowseScreen(
                initialSourceId = args.getString(ARG_SOURCE_ID),
                onSelectNovel = { sourceId, novelUrl ->
                    router.pushController(NovelDetailsController(sourceId, novelUrl).withFadeTransaction())
                },
            ),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        const val title = "LN browse"
        private const val ARG_SOURCE_ID = "sourceId"
    }
}
