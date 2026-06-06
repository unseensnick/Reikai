package eu.kanade.tachiyomi.ui.reader.compose

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import yokai.presentation.reader.ReaderScreen

/**
 * Conductor host for the unified Compose reader (Phase 1, novel content). Carries the displayed
 * chapter order (reading order) and the tapped chapter id via the Bundle args, mirroring
 * [eu.kanade.tachiyomi.ui.novel.NovelDetailsController]. The reader resolves each chapter's source
 * itself, so prev/next can cross sources in a merged novel.
 */
class ReaderController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    constructor(chapterIds: LongArray, initialChapterId: Long) : this(
        Bundle().apply {
            putLongArray(ARG_CHAPTER_IDS, chapterIds)
            putLong(ARG_CHAPTER_ID, initialChapterId)
        },
    )

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = ReaderScreen(
                chapterIds = args.getLongArray(ARG_CHAPTER_IDS)?.toList().orEmpty(),
                initialChapterId = args.getLong(ARG_CHAPTER_ID),
            ),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        private const val ARG_CHAPTER_IDS = "chapterIds"
        private const val ARG_CHAPTER_ID = "chapterId"
    }
}
