package eu.kanade.tachiyomi.ui.novel.browse

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.browse.NovelBrowseScreen

class NovelBrowseController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    constructor(sourceId: String) : this(
        Bundle().apply { putString(ARG_SOURCE_ID, sourceId) },
    )

    @Composable
    override fun ScreenContent() {
        // The screen routes to details itself via LocalRouter; no lambda is passed in (Voyager
        // serializes the screen into saved state, and a captured lambda isn't serializable).
        Navigator(
            screen = NovelBrowseScreen(initialSourceId = args.getString(ARG_SOURCE_ID)),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        const val title = "LN browse"
        private const val ARG_SOURCE_ID = "sourceId"
    }
}
