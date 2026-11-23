package reikai.presentation.reader

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.library.ContentType
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
import reikai.domain.reader.readerChapterFilters
import reikai.domain.source.isInstalled
import reikai.novel.download.NovelDownloadCache
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
        val marks = copyMarks.loadedMarks(ContentType.NOVELS)
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
}
