package eu.kanade.tachiyomi.ui.novel.globalsearch

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.globalsearch.NovelGlobalSearchScreen

/** Conductor bridge hosting the LN global-search Compose screen, mirroring NovelBrowseController. */
class NovelGlobalSearchController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    constructor(query: String) : this(
        Bundle().apply { putString(ARG_QUERY, query) },
    )

    @Composable
    override fun ScreenContent() {
        // The screen routes to details itself via LocalRouter; no lambda is passed in (Voyager
        // serializes the screen into saved state, and a captured lambda isn't serializable).
        Navigator(
            screen = NovelGlobalSearchScreen(initialQuery = args.getString(ARG_QUERY).orEmpty()),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        private const val ARG_QUERY = "query"
    }
}
