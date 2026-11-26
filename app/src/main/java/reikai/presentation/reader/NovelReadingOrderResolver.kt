package reikai.presentation.reader

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.library.StoredType
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelOrderInputs
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelReadingOrder
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.novelReadingOrder
import reikai.domain.novel.ownersOf
import reikai.domain.reader.chaptersToDownloadAhead
import reikai.domain.reader.readerChapterFilters
import reikai.domain.source.isInstalled
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager

/**
 * Reads what [novelReadingOrder] needs from the database and the settings, for the reader and for read
 * aloud going on with no reader, so the two cannot step through a series differently.
 */
@Inject
class NovelReadingOrderResolver(
    private val getNextNovelChapter: GetNextNovelChapter,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val copyMarks: ChapterCopyMarkRepository,
    private val novelPreferences: NovelPreferences,
    private val basePreferences: BasePreferences,
    private val novelDownloadCache: NovelDownloadCache,
    private val sourceManager: NovelSourceManager,
) {

    suspend fun resolve(novelId: Long, currentId: Long, sourceScoped: Boolean): NovelReadingOrder {
        val reading = getNextNovelChapter.readingRows(
            novelId,
            sourceScoped,
            { sourceManager.isInstalled(it.source) },
        ) { chapters, owners -> novelDownloadCache.downloadedChapterIds(chapters, owners) }
        val known = reading.rows + reading.pooled
        val mapped = reading.copies?.takeIf { !sourceScoped }?.idOf(currentId) ?: currentId
        val opened = known.find { it.id == mapped } ?: chapterRepo.getById(mapped)
        val all = known + listOfNotNull(opened)
        val marks = copyMarks.loadedMarks(StoredType.NOVELS)
        val downloadedOnly = basePreferences.downloadedOnly.get()
        return novelReadingOrder(
            NovelOrderInputs(
                currentId = currentId,
                scope = MergeScope.of(sourceScoped),
                reading = reading,
                opened = opened,
                readingOrder = getNextNovelChapter.readingOrder(novelId),
                isHidden = getNextNovelChapter.hiddenAmong(all),
                markOf = { chapter -> marks[chapter.novelId]?.get(chapter.url) },
                owners = novelRepo.ownersOf(all),
                onDiskAmong = { chapters, owners -> novelDownloadCache.downloadedChapterIds(chapters, owners) },
                skipRead = novelPreferences.readerSkipRead().get(),
                skipFiltered = novelPreferences.readerSkipFiltered().get(),
                skipDuplicates = novelPreferences.readerSkipDuplicateChapters().get(),
                downloadedOnly = downloadedOnly,
                filters = getNextNovelChapter.chapterSettings(novelId)
                    ?.readerChapterFilters(novelPreferences, downloadedOnly),
            ),
        )
    }

    /**
     * Enqueues the next N unread, un-downloaded chapters after [currentId] in [order]'s reading order, the
     * novel twin of manga's autoDownloadWhileReading, pinned by [chaptersToDownloadAhead]. Runs in
     * incognito, which keeps history out and not downloads, as in manga. Unlike manga it needs neither
     * chapter on disk: manga's gate keeps a streamed chapter's page loads from sharing the source with the
     * download, and a novel chapter is one request. Read fresh, so a chapter finished here is not queued again.
     */
    suspend fun downloadAhead(order: NovelReadingOrder, currentId: Long, downloads: NovelDownloadManager) {
        val ahead = novelPreferences.autoDownloadWhileReading().get()
        if (ahead <= 0) return
        val index = order.aheadIds.indexOf(currentId)
        if (index < 0) return
        val pooled = order.memberIds.flatMap { chapterRepo.getByNovelId(it) }
        val byId = pooled.associateBy { it.id }
        val candidates = order.aheadIds.drop(index + 1).mapNotNull { byId[it] ?: chapterRepo.getById(it) }
        val novels = novelRepo.ownersOf(pooled + candidates)
        val flags = GroupChapterFlags(order.scope, pooled, candidates, order.stitch, { it.id }, { it.read }, {
            it.bookmark
        }) { novelDownloadCache.downloadedChapterIds(pooled, novels) }
        // Already on disk is left to downloadChapters, which drops it, as manga's queue does.
        val picked = chaptersToDownloadAhead(candidates, from = 0, count = ahead, isRead = flags::isRead)
        val toDownload = order.downloadTargets.of(picked, pooled + picked) { it.id }
        if (toDownload.isNotEmpty()) downloads.downloadChapters(toDownload)
    }
}
