package reikai.domain.novel

import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter

/**
 * The novel each of [chapters] belongs to, keyed by id and read once per novel, since a merged list
 * pools several. A novel no longer stored is absent, so its chapters resolve to nothing.
 */
suspend fun NovelRepository.ownersOf(chapters: List<NovelChapter>): Map<Long, Novel> =
    chapters.mapTo(LinkedHashSet()) { it.novelId }.mapNotNull { getById(it) }.associateBy { it.id }
