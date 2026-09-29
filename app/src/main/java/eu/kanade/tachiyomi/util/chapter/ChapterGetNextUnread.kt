package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import reikai.domain.chapter.ReadingOrder
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.downloadedChapterIds
import reikai.domain.manga.inReadingOrder
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Gets next unread chapter with filters and sorting applied
 */
// RK: merged-series and hidden-chapter aware, resuming in the reader's order
fun List<Chapter>.getNextUnread(
    manga: Manga,
    downloadManager: DownloadManager,
    // RK --> the merge group this list is the stitch of, whose other sources answer read, bookmarked and
    // on disk, as the details list does. The library resumes in group scope, so any copy counts.
    group: MergedChapterProvider.Group? = null,
    // RK: the hidden-chapter keys, passed over unless only hidden chapters are left unread.
    hiddenKeys: Set<String> = emptySet(),
): Chapter? {
    val mangaById = group?.mangaById.orEmpty()
    val ownerOf = { chapter: Chapter -> mangaById[chapter.mangaId] ?: manga }
    val pooled = group?.pooledChapters ?: this
    val flags = GroupChapterFlags(
        MergeScope.Group,
        pooled,
        this,
        group?.stitch.orEmpty(),
        { it.id },
        { it.read },
        { it.bookmark },
    ) { downloadManager.downloadedChapterIds(pooled, ownerOf) }
    val shown = applyFilters(manga, flags)
    // RK <--
    // RK: the order the reader pages in, asked the question novels resume by, hidden chapters last.
    val isHidden = { chapter: Chapter ->
        hiddenChapterKey(ownerOf(chapter).source.toString(), chapter.url) in hiddenKeys
    }
    return ReadingOrder.nextToRead(ReadingOrder.hiddenLast(shown.inReadingOrder(manga), isHidden)) {
        flags.isRead(it) // RK
    }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    // RK: as above, through the reader's order. Filtered on the item, whose read flag spans the group.
    return applyFilters(manga).filterNot { it.isRead }.map { it.chapter }.toList().inReadingOrder(manga).firstOrNull()
}
