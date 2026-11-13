package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository

/**
 * Per-novel reader viewer-flag writes, the novel twin of
 * [eu.kanade.domain.manga.interactor.SetMangaViewerFlags], pinned by ViewerOrientationConformanceTest.
 * Novels carry the orientation bits, their own rendering mode and their own reading mode ([NovelPageLayout]).
 * Each write is one masked update in the database rather than a read then a write, so two quick choices
 * on different fields cannot overwrite each other.
 */
@Inject
class SetNovelViewerFlags(
    private val novelRepository: NovelRepository,
) {

    suspend fun awaitSetOrientation(id: Long, flag: Long) {
        novelRepository.setViewerFlags(id, flag, ReaderOrientation.MASK.toLong())
    }

    /** Keeps [mode] for this novel, or follows the rendering-mode setting again when it is null. */
    suspend fun awaitSetRenderingMode(id: Long, mode: NovelRenderingMode?) {
        novelRepository.setViewerFlags(id, NovelRenderingMode.flagOf(mode), NovelRenderingMode.MASK)
    }

    /** Keeps [layout] for this novel, or follows the reading-mode setting again when it is null. */
    suspend fun awaitSetPageLayout(id: Long, layout: NovelPageLayout?) {
        novelRepository.setViewerFlags(id, NovelPageLayout.flagOf(layout), NovelPageLayout.MASK)
    }
}
