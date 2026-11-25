package reikai.domain.novel

import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter

/**
 * The novel each of [chapters] belongs to, keyed by id and read in one batch, since a merged list or a
 * selection pools several. A novel no longer stored is absent, so its chapters resolve to nothing.
 */
suspend fun NovelRepository.ownersOf(chapters: List<NovelChapter>): Map<Long, Novel> =
    getByIds(chapters.map { it.novelId }.distinct()).associateBy { it.id }
