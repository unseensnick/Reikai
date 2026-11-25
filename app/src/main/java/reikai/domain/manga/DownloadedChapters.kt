package reikai.domain.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import reikai.domain.download.isOwnedOnDevice
import reikai.domain.download.ownedOnDisk
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal

/** Ids of [chapters] whose own file is on disk, each probed under its own source's manga ([ownedOnDisk]). */
fun DownloadManager.downloadedChapterIds(chapters: List<Chapter>, ownerOf: (Chapter) -> Manga): Set<Long> =
    ownedOnDisk(chapters, { it.mangaId }, ownerOf, { it.isLocal() }, { it.id }) { owner, owned ->
        getDownloadedChapterIds(owned, owner)
    }

/** Whether one copy of a chapter is on the device, by [ownedOnDisk]'s rule. */
fun DownloadManager.isOnDevice(
    chapterName: String,
    scanlator: String?,
    chapterUrl: String,
    ownerTitle: String,
    ownerSource: Long,
): Boolean = isOwnedOnDevice(ownerSource == LocalSource.ID) {
    isChapterDownloaded(chapterName, scanlator, chapterUrl, ownerTitle, ownerSource)
}
