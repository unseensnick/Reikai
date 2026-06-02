package eu.kanade.tachiyomi.ui.novel

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.novel.details.NovelDetailsScreen

/**
 * Library-tap entry point into the novel details + chapter reader flow. Carries the source id
 * (lnreader plugin.id) and the novel's source-relative URL via the Conductor Bundle args.
 */
class NovelDetailsController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    constructor(sourceId: String, novelUrl: String) : this(
        Bundle().apply {
            putString(ARG_SOURCE_ID, sourceId)
            putString(ARG_NOVEL_URL, novelUrl)
        },
    )

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = NovelDetailsScreen(
                sourceId = args.getString(ARG_SOURCE_ID).orEmpty(),
                novelUrl = args.getString(ARG_NOVEL_URL).orEmpty(),
            ),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        private const val ARG_SOURCE_ID = "sourceId"
        private const val ARG_NOVEL_URL = "url"
    }
}
