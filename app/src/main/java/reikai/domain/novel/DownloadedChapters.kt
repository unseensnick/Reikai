package reikai.domain.novel

import reikai.domain.download.isOwnedOnDevice
import reikai.domain.download.ownedOnDisk
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadCache
import reikai.novel.local.LocalNovelSource

/** Ids of [chapters] on disk, each probed under its own novel in [owners] (see [ownersOf]), by [ownedOnDisk]. */
fun NovelDownloadCache.downloadedChapterIds(chapters: List<NovelChapter>, owners: Map<Long, Novel>): Set<Long> =
    ownedOnDisk(
        chapters,
        ownerKey = { it.novelId },
        ownerOf = { owners[it.novelId] },
        isLocal = { LocalNovelSource.isLocal(it.source) },
        id = { it.id },
    ) { owner, owned -> downloadedChapterIds(owner, owned) }

/** Whether one copy of a chapter is on the device, by [ownedOnDisk]'s rule. */
fun NovelDownloadCache.isOnDevice(source: String, novelTitle: String, chapterName: String, chapterUrl: String) =
    isOwnedOnDevice(LocalNovelSource.isLocal(source)) {
        isChapterDownloaded(source, novelTitle, chapterName, chapterUrl)
    }

/** [isOnDevice] for [chapter] under its own [owner]. */
fun NovelDownloadCache.isOnDevice(owner: Novel, chapter: NovelChapter): Boolean =
    isOwnedOnDevice(LocalNovelSource.isLocal(owner.source)) { isChapterDownloaded(owner, chapter) }
