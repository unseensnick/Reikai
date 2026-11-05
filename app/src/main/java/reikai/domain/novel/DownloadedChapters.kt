package reikai.domain.novel

import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import reikai.novel.local.LocalNovelSource

/**
 * Ids of [chapters] on disk, each probed under its own novel in [owners] (see [ownersOf]), as manga's
 * [reikai.domain.manga.downloadedChapterIds] probes each copy under its own source: a merged list pools
 * several novels' chapters. A chapter whose novel is missing from [owners] is not on disk, and a local
 * novel's chapters all are, as a local manga's are.
 */
fun NovelDownloadCache.downloadedChapterIds(chapters: List<NovelChapter>, owners: Map<Long, Novel>): Set<Long> =
    chapters.groupBy { it.novelId }.flatMapTo(HashSet()) { (novelId, owned) ->
        val owner = owners[novelId] ?: return@flatMapTo emptyList()
        if (LocalNovelSource.isLocal(owner.source)) owned.map { it.id } else downloadedChapterIds(owner, owned)
    }
