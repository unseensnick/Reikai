package reikai.domain.manga

import eu.kanade.tachiyomi.data.download.DownloadManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal

/**
 * Ids of [chapters] whose own file is on disk, each probed against the source that copy came from
 * rather than the screen's manga: a merged series' chapter is stored under its own source's folder,
 * so probing them all against one manga's reports every sibling's chapter as missing.
 *
 * Grouped by owner so each owner's folder is looked up once for all its chapters.
 */
fun DownloadManager.downloadedChapterIds(chapters: List<Chapter>, ownerOf: (Chapter) -> Manga): Set<Long> =
    chapters.groupBy(ownerOf).flatMapTo(HashSet()) { (owner, owned) ->
        if (owner.isLocal()) owned.map { it.id } else getDownloadedChapterIds(owned, owner)
    }

/**
 * Whether one copy of a chapter is on the device, by [downloadedChapterIds]' rule: a local owner's
 * chapters are its own files, so they always are. For a caller holding only a copy's stored values.
 */
fun DownloadManager.isOnDevice(
    chapterName: String,
    scanlator: String?,
    chapterUrl: String,
    ownerTitle: String,
    ownerSource: Long,
): Boolean = ownerSource == LocalSource.ID ||
    isChapterDownloaded(chapterName, scanlator, chapterUrl, ownerTitle, ownerSource)
