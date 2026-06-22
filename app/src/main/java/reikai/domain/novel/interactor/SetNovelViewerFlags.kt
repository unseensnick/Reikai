package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.setNovelFlag

/**
 * Per-novel reader viewer-flag writes, the novel twin of
 * [eu.kanade.domain.manga.interactor.SetMangaViewerFlags]. Novels carry only the orientation bits
 * (the reader is text-based, so there is no reading-mode). Reads the current flags and writes only
 * the [viewer_flags] column via a [NovelUpdate].
 */
class SetNovelViewerFlags(
    private val novelRepository: NovelRepository,
) {

    suspend fun awaitSetOrientation(id: Long, flag: Long) {
        val novel = novelRepository.getById(id) ?: return
        novelRepository.update(
            NovelUpdate(
                id = id,
                viewerFlags = setNovelFlag(novel.viewerFlags, flag, ReaderOrientation.MASK.toLong()),
            ),
        )
    }
}
