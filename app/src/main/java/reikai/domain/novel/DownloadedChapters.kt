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

/**
 * Whether one copy of a chapter is on the device, by [downloadedChapterIds]' rule: a local novel's
 * chapters are its own files, so they always are. For a caller holding only a copy's stored values.
 */
fun NovelDownloadCache.isOnDevice(source: String, novelTitle: String, chapterName: String, chapterUrl: String) =
    LocalNovelSource.isLocal(source) || isChapterDownloaded(source, novelTitle, chapterName, chapterUrl)

/** [isOnDevice] for [chapter] under its own [owner]. */
fun NovelDownloadCache.isOnDevice(owner: Novel, chapter: NovelChapter): Boolean =
    LocalNovelSource.isLocal(owner.source) || isChapterDownloaded(owner, chapter)
