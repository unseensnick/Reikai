package eu.kanade.tachiyomi.ui.reader.compose

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.reader.ReaderScreen

/**
 * Conductor host for the unified Compose reader (Phase 1, novel content). Carries the chapter's
 * owning source id (lnreader plugin.id) and the stored chapter row id via the Bundle args, mirroring
 * [eu.kanade.tachiyomi.ui.novel.NovelDetailsController].
 */
class ReaderController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    constructor(sourceId: String, chapterId: Long) : this(
        Bundle().apply {
            putString(ARG_SOURCE_ID, sourceId)
            putLong(ARG_CHAPTER_ID, chapterId)
        },
    )

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = ReaderScreen(
                sourceId = args.getString(ARG_SOURCE_ID).orEmpty(),
                chapterId = args.getLong(ARG_CHAPTER_ID),
            ),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        private const val ARG_SOURCE_ID = "sourceId"
        private const val ARG_CHAPTER_ID = "chapterId"
    }
}
