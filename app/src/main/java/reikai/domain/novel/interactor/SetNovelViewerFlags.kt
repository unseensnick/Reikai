package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.setNovelFlag

/**
 * Per-novel reader viewer-flag writes, the novel twin of
 * [eu.kanade.domain.manga.interactor.SetMangaViewerFlags], pinned by ViewerOrientationConformanceTest.
 * Novels carry the orientation bits and their own rendering mode (the reader is text-based, so there is
 * no reading-mode).
 * Reads the current flags and writes only the [viewer_flags] column via a [NovelUpdate].
 */
@Inject
class SetNovelViewerFlags(
    private val novelRepository: NovelRepository,
) {

    suspend fun awaitSetOrientation(id: Long, flag: Long) {
        val novel = novelRepository.getById(id) ?: return
        novelRepository.update(
            NovelUpdate(id) { viewerFlags = setNovelFlag(novel.viewerFlags, flag, ReaderOrientation.MASK.toLong()) },
        )
    }

    /** Keeps [mode] for this novel, or follows the rendering-mode setting again when it is null. */
    suspend fun awaitSetRenderingMode(id: Long, mode: NovelRenderingMode?) {
        val novel = novelRepository.getById(id) ?: return
        novelRepository.update(
            NovelUpdate(id) {
                viewerFlags = setNovelFlag(novel.viewerFlags, NovelRenderingMode.flagOf(mode), NovelRenderingMode.MASK)
            },
        )
    }
}
