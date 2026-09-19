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
    ) { queue, downloadingId, completed -> Triple(queue, downloadingId, completed) }
        .mapLatest { (queue, downloadingId, completed) ->
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
            )
        }
        .flowOn(Dispatchers.IO)

    override suspend fun chapterNames(seriesId: Long, chapterIds: Collection<Long>): Map<Long, String> {
        val cached = chapterNames[seriesId]
        // Read again only when a chapter was queued after the names were.
        if (cached != null && cached.keys.containsAll(chapterIds)) return cached
        return chaptersOf(seriesId).associate { it.id to it.name }.also { chapterNames[seriesId] = it }
    }

    override suspend fun detailsScreen(seriesId: Long): Screen? =
        novelRepo.getById(seriesId)?.let { NovelScreen(it.source, it.url) }

    override fun cancelChapter(chapterId: Long) = downloadManager.cancelDownloads(listOf(chapterId))

    override fun downloadNow(chapterId: Long) = downloadManager.startDownloadNow(chapterId)

    override fun moveChapterToBottom(chapterId: Long) {
        val queue = downloadManager.queueState.value
        val moved = queue.withChapterLastInSeries(chapterId, { it.chapterId }, { it.novelId })
        if (moved != queue) downloadManager.reorderQueue(moved)
    }

    override fun reorderSeries(seriesIdsInOrder: List<Long>) {
        val bySeries = downloadManager.queueState.value.groupBy { it.novelId }
        val named = seriesIdsInOrder.toSet()
        val reordered = (seriesIdsInOrder + bySeries.keys.filter { it !in named })
            .flatMap { bySeries[it].orEmpty() }
        downloadManager.reorderQueue(reordered)
    }

    override fun cancelSeries(seriesId: Long) = downloadManager.cancelDownloads(
        downloadManager.queueState.value.filter { it.novelId == seriesId }.map { it.chapterId },
    )

    override fun cancelAll() = downloadManager.cancelAllDownloads()

    override fun pause() = downloadManager.pauseDownloads()

    override fun start() = downloadManager.startDownloads()

    override suspend fun sort(key: DownloadQueueSortKey, descending: Boolean) {
        val queue = downloadManager.queueState.value
        val chapterById = queue.mapTo(HashSet()) { it.novelId }
            .flatMap { chaptersOf(it) }
            .associateBy { it.id }
        val sorted = queue.sortedWithinSeries(
            seriesOf = { it.novelId },
            keyOf = { download ->
                chapterById[download.chapterId]?.let {
                    when (key) {
                        DownloadQueueSortKey.UPLOAD_DATE -> it.dateUpload.toDouble()
                        DownloadQueueSortKey.CHAPTER_NUMBER -> it.chapterNumber
                    }
                }
            },
            descending = descending,
        )
        downloadManager.reorderQueue(sorted)
    }

    private suspend fun labelOf(novelId: Long): QueuedSeriesLabel = labels[novelId]
        ?: run {
            val novel = novelRepo.getById(novelId)
            val sourceId = novel?.source.orEmpty()
            val sourceName = sourceManager.get(sourceId)?.name?.ifBlank { null } ?: sourceId
            QueuedSeriesLabel(novel?.title.orEmpty(), sourceName)
        }.also { labels[novelId] = it }

    private suspend fun chaptersOf(novelId: Long): List<NovelChapter> = chapterRepo.getByNovelId(novelId)
}
