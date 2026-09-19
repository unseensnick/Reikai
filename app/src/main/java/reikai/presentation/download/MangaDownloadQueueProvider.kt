package reikai.presentation.download

import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import reikai.domain.library.ContentType
import tachiyomi.domain.download.service.DownloadPreferences

/** The manga downloader under the download queue, over Mihon's [DownloadManager]. */
@Inject
class MangaDownloadQueueProvider(
    private val downloadManager: DownloadManager,
    private val downloader: Downloader,
    private val downloadPreferences: DownloadPreferences,
) : DownloadQueueProvider {

    override val contentType = ContentType.MANGA

    override val isRunning: Flow<Boolean> get() = downloadManager.isDownloaderRunning

    override val snapshots: Flow<DownloadQueueSnapshot> = channelFlow {
        var shownNonEmpty = false
        combine(
            downloadManager.queueState,
            downloadManager.isDownloaderRunning,
            downloadPreferences.parallelSourceLimit.changes(),
            downloader.completions.counts,
            // queueState does not re-emit when one download's status or page progress changes.
            merge(
                downloadManager.statusFlow().map { },
                downloadManager.progressFlow().map { }.sample(PROGRESS_SAMPLE_MS),
            ).onStart { emit(Unit) },
        ) { queue, running, sourceLimit, completed, _ ->
            Triple(queue, if (running) sourceLimit else 0, completed)
        }.collectLatest { (queue, sourceLimit, completed) ->
            // A reorder clears Mihon's queue and adds it back, which would flash the empty screen. A
            // repopulate cancels this wait; a real cancel-all still lands after it.
            if (queue.isEmpty() && shownNonEmpty) delay(TRANSIENT_EMPTY_DEBOUNCE_MS)
            send(snapshotOf(queue, sourceLimit, completed))
            shownNonEmpty = queue.isNotEmpty()
        }
    }

    override suspend fun chapterNames(seriesId: Long, chapterIds: Collection<Long>): Map<Long, String> =
        downloadManager.queueState.value
            .filter { it.manga.id == seriesId }
            .associate { it.chapter.id to it.chapter.name }

    override suspend fun detailsScreen(seriesId: Long): Screen = MangaScreen(seriesId)

    override fun cancelChapter(chapterId: Long) {
        downloadManager.getQueuedDownloadOrNull(chapterId)?.let { downloadManager.cancelQueuedDownloads(listOf(it)) }
    }

    override fun downloadNow(chapterId: Long) = downloadManager.startDownloadNow(chapterId)

    override fun reorderSeries(seriesIdsInOrder: List<Long>) {
        val bySeries = downloadManager.queueState.value.groupBy { it.manga.id }
        val named = seriesIdsInOrder.toSet()
        val reordered = (seriesIdsInOrder + bySeries.keys.filter { it !in named })
            .flatMap { bySeries[it].orEmpty() }
        downloadManager.reorderQueue(reordered)
    }

    override fun cancelSeries(seriesId: Long) {
        val downloads = downloadManager.queueState.value.filter { it.manga.id == seriesId }
        if (downloads.isNotEmpty()) downloadManager.cancelQueuedDownloads(downloads)
    }

    override fun cancelAll() = downloadManager.clearQueue()

    override fun pause() = downloadManager.pauseDownloads()

    override fun start() = downloadManager.startDownloads()

    override suspend fun sort(key: DownloadQueueSortKey, descending: Boolean) {
        val sorted = downloadManager.queueState.value.sortedWithinSeries(
            seriesOf = { it.manga.id },
            keyOf = {
                when (key) {
                    DownloadQueueSortKey.UPLOAD_DATE -> it.chapter.dateUpload.toDouble()
                    DownloadQueueSortKey.CHAPTER_NUMBER -> it.chapter.chapterNumber
                }
            },
            descending = descending,
        )
        downloadManager.reorderQueue(sorted)
    }

    companion object {
        // Long enough to swallow the reorder's clear-then-re-add, short enough to be imperceptible on a
        // real cancel-all.
        private const val TRANSIENT_EMPTY_DEBOUNCE_MS = 150L

        // Page progress moves many times a second; the chapter sheet only needs to look live.
        private const val PROGRESS_SAMPLE_MS = 500L
    }
}

/**
 * The series the downloader works on now, by its own selection rule: the front pending chapter of each
 * of the first [sourceLimit] sources in queue order. A chapter's DOWNLOADING flag lags that selection
 * and drops out between chapters, so it is not used. [sourceLimit] is 0 while the downloader is stopped.
 */
private fun snapshotOf(queue: List<Download>, sourceLimit: Int, completed: Map<Long, Int>): DownloadQueueSnapshot {
    val active = queue.asSequence()
        .filter { it.status.value <= Download.State.DOWNLOADING.value }
        .groupBy { it.source.id }
        .values
        .take(sourceLimit)
        .mapNotNullTo(HashSet()) { it.firstOrNull()?.manga?.id }
    return DownloadQueueSnapshot(
        chapters = queue.map { download ->
            QueuedChapter(
                seriesId = download.manga.id,
                chapterId = download.chapter.id,
                status = when (download.status) {
                    Download.State.DOWNLOADING -> QueuedChapterStatus.DOWNLOADING
                    Download.State.ERROR -> QueuedChapterStatus.ERROR
                    else -> QueuedChapterStatus.QUEUED
                },
                pages = download.pages?.let { PageProgress(download.downloadedImages, it.size, download.progress) },
                failure = download.failure,
            )
        },
        activeSeries = active,
        completed = completed,
        labels = queue.associate { it.manga.id to QueuedSeriesLabel(it.manga.title, it.source.name) },
    )
}
