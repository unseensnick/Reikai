package reikai.presentation.download

import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.details.NovelScreen
import java.util.concurrent.ConcurrentHashMap

/** The novel downloader under the download queue, over [NovelDownloadManager]. */
@Inject
class NovelDownloadQueueProvider(
    private val downloadManager: NovelDownloadManager,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val sourceManager: NovelSourceManager,
) : DownloadQueueProvider {

    override val contentType = ContentType.NOVELS

    override val isRunning: Flow<Boolean> get() = downloadManager.isDownloaderRunning

    // Labels and chapter names per novel, so the progress-driven emissions don't re-read the database.
    private val labels = ConcurrentHashMap<Long, QueuedSeriesLabel>()
    private val chapterNames = ConcurrentHashMap<Long, Map<Long, String>>()

    override val snapshots: Flow<DownloadQueueSnapshot> = combine(
        downloadManager.queueState,
        downloadManager.downloadingNovelId,
        downloadManager.completions.counts,
    ) { _, downloadingId, completed ->
        val (queue, restored) = readQueueAfterRestore({ downloadManager.isQueueRestored }, downloadManager.queueState)
        QueueRead(queue, downloadingId, completed, restored)
    }
        .mapLatest { (queue, downloadingId, completed, restored) ->
            val queued = queue.mapTo(HashSet()) { it.novelId }
            labels.keys.retainAll(queued)
            chapterNames.keys.retainAll(queued)
            DownloadQueueSnapshot(
                chapters = queue.map { download ->
                    QueuedChapter(
                        seriesId = download.novelId,
                        chapterId = download.chapterId,
                        status = when (download.state) {
                            NovelDownload.State.QUEUE -> QueuedChapterStatus.QUEUED
                            NovelDownload.State.DOWNLOADING -> QueuedChapterStatus.DOWNLOADING
                            NovelDownload.State.ERROR -> QueuedChapterStatus.ERROR
                        },
                        failure = download.failure,
                    )
                },
                // Latched across the pacing gap between chapters, so the card does not flicker to Queued.
                activeSeries = setOfNotNull(downloadingId),
                completed = completed,
                labels = queued.associateWith { labelOf(it) },
                restored = restored,
            )
        }
        .flowOn(Dispatchers.Default)

    override suspend fun chapterNames(seriesId: Long, chapterIds: Collection<Long>): Map<Long, String> {
        val cached = chapterNames[seriesId]
        // Read again only when a chapter was queued after the names were.
        if (cached != null && cached.keys.containsAll(chapterIds)) return cached
        return chaptersOf(seriesId).associate { it.id to it.name }.also { chapterNames[seriesId] = it }
    }

    override suspend fun detailsScreen(seriesId: Long): Screen? =
        NovelScreen.forStored(seriesId, novelRepo)

    override suspend fun cancelChapter(chapterId: Long) = downloadManager.cancelDownloads(listOf(chapterId))

    override suspend fun downloadNow(chapterId: Long) = downloadManager.startDownloadNow(chapterId)

    override suspend fun moveChapterToBottom(chapterId: Long) {
        val queue = downloadManager.queueState.value
        val moved = queue.withChapterLastInSeries(chapterId, { it.chapterId }, { it.novelId })
        if (moved != queue) downloadManager.reorderQueue(moved)
    }

    override suspend fun reorderSeries(seriesIdsInOrder: List<Long>) {
        val reordered = downloadManager.queueState.value.withSeriesInOrder(seriesIdsInOrder) { it.novelId }
        downloadManager.reorderQueue(reordered)
    }

    override suspend fun cancelSeries(seriesId: Long) = downloadManager.cancelDownloads(
        downloadManager.queueState.value.filter { it.novelId == seriesId }.map { it.chapterId },
    )

    override suspend fun cancelAll() = downloadManager.cancelAllDownloads()

    override suspend fun pause() = downloadManager.pauseDownloads()

    override fun start() = downloadManager.startDownloads()

    override suspend fun sort(key: DownloadQueueSortKey, descending: Boolean) {
        val queue = downloadManager.queueState.value
        val chapterById = queue.mapTo(HashSet()) { it.novelId }
            .flatMap { chaptersOf(it) }
            .associateBy { it.id }
        val sorted = queue.sortedWithinSeries(
            seriesOf = { it.novelId },
            keyOf = { download ->
                chapterById[download.chapterId]?.let { key.sortValue(it.dateUpload, it.chapterNumber) }
            },
            descending = descending,
        )
        downloadManager.reorderQueue(sorted)
    }

    private suspend fun labelOf(novelId: Long): QueuedSeriesLabel = labels[novelId]
        ?: run {
            val novel = novelRepo.getById(novelId)
            QueuedSeriesLabel(novel?.title.orEmpty(), sourceManager.nameOf(novel?.source.orEmpty()))
        }.also { labels[novelId] = it }

    private suspend fun chaptersOf(novelId: Long): List<NovelChapter> = chapterRepo.getByNovelId(novelId)
}

private data class QueueRead(
    val queue: List<NovelDownload>,
    val downloadingId: Long?,
    val completed: Map<Long, Int>,
    val restored: Boolean,
)
