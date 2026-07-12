package reikai.presentation.download

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.injectLazy

/**
 * Backs the manga side of the unified download queue on the shared [EntryDownloadCardList]: aggregates
 * Mihon's per-chapter [DownloadManager.queueState] into one card per manga (title + owning source + a
 * downloaded/total count + a single status), the manga twin of [NovelDownloadQueueScreenModel]. Mihon's
 * own [eu.kanade.tachiyomi.ui.download.DownloadQueueScreenModel] and its RecyclerView adapter/holders
 * are the parked per-chapter view, left inert.
 */
class MangaDownloadQueueScreenModel :
    StateScreenModel<List<EntryDownloadCardUi>>(emptyList()) {

    private val downloadManager: DownloadManager by injectLazy()
    private val downloadPreferences: DownloadPreferences by injectLazy()

    val isDownloaderRunning: StateFlow<Boolean> = downloadManager.isDownloaderRunning
        .stateIn(screenModelScope, SharingStarted.Eagerly, false)

    // Highest queued count ever seen per manga: the "total" the card counts down from. Completed
    // chapters leave the queue, so the remaining count alone can't give a stable downloaded/total.
    private val initialTotals = HashMap<Long, Int>()

    init {
        // Recompute on anything that shifts the active set: the queue (order / membership), any
        // per-download status change (which queueState does not re-emit), the run state, or the
        // concurrency limit. A chapter's DOWNLOADING flag is an unreliable "active" signal (it lags
        // selection and drops out in the gap between chapters), so status is derived from the
        // downloader's own selection rule instead, which is gap-free and follows the queue order.
        screenModelScope.launchIO {
            merge(
                downloadManager.queueState.map { },
                downloadManager.statusFlow().map { },
                isDownloaderRunning.map { },
                downloadPreferences.parallelSourceLimit.changes().map { },
            ).collectLatest {
                // A reorder momentarily clears Mihon's queue (updateQueue tears it down then re-adds it
                // in the new order), which would flash the "no downloads" screen. If we just went empty,
                // wait a beat: a repopulate cancels this via collectLatest so the empty never shows; a
                // genuine clear (cancel-all) still falls through after the wait.
                if (downloadManager.queueState.value.isEmpty() && state.value.isNotEmpty()) {
                    delay(TRANSIENT_EMPTY_DEBOUNCE_MS)
                }
                val queue = downloadManager.queueState.value
                val byManga = queue.groupBy { it.manga.id }
                initialTotals.keys.retainAll(byManga.keys)
                // Replicate Downloader.launchDownloaderJob: it downloads the front-most pending chapter
                // of each of the first parallelSourceLimit distinct sources, so those sources' front
                // mangas are the active ones. One chapter per source, so mangas sharing a source contend
                // for its single slot. Reorder re-prioritizes (updateQueue restarts selection), so this
                // stays correct across drags.
                val activeMangaIds: Set<Long> = if (isDownloaderRunning.value) {
                    queue.asSequence()
                        .filter { it.status.value <= Download.State.DOWNLOADING.value }
                        .groupBy { it.source.id }
                        .values
                        .take(downloadPreferences.parallelSourceLimit.get())
                        .mapNotNull { it.firstOrNull()?.manga?.id }
                        .toHashSet()
                } else {
                    emptySet()
                }
                mutableState.value = byManga.map { (mangaId, downloads) ->
                    val remaining = downloads.size
                    val total = maxOf(initialTotals[mangaId] ?: 0, remaining)
                        .also { initialTotals[mangaId] = it }
                    val status = when {
                        mangaId in activeMangaIds -> EntryDownloadCardStatus.DOWNLOADING
                        downloads.all { it.status == Download.State.ERROR } -> EntryDownloadCardStatus.ERROR
                        else -> EntryDownloadCardStatus.QUEUED
                    }
                    val first = downloads.first()
                    EntryDownloadCardUi(
                        contentType = ContentType.MANGA,
                        seriesId = mangaId,
                        sourceName = first.source.name,
                        title = first.manga.title,
                        downloadedChapters = (total - remaining).coerceAtLeast(0),
                        totalChapters = total,
                        status = status,
                    )
                }
            }
        }
    }

    fun startDownloads() = downloadManager.startDownloads()

    fun pauseDownloads() = downloadManager.pauseDownloads()

    fun cancelAll() = downloadManager.clearQueue()

    /** Cancel every queued chapter of one manga (the card's cancel action). */
    fun cancelSeries(mangaId: Long) {
        val downloads = downloadManager.queueState.value.filter { it.manga.id == mangaId }
        if (downloads.isNotEmpty()) downloadManager.cancelQueuedDownloads(downloads)
    }

    /** Commit a new series order from a card drag / move-to-top / move-to-bottom: expand each manga back
     *  into its block of queued chapters, in the given order, and persist. Any manga not named in
     *  [order] is appended, so a series enqueued mid-gesture is never dropped. */
    fun reorderBySeries(order: List<Long>) {
        val byManga = downloadManager.queueState.value.groupBy { it.manga.id }
        val named = LinkedHashSet(order)
        val reordered = (order + byManga.keys.filter { it !in named })
            .flatMap { byManga[it].orEmpty() }
        downloadManager.reorderQueue(reordered)
    }

    /** Sort each manga's queued chapters among themselves by [selector] (manga order preserved), so the
     *  drain picks them in that order. */
    fun <R : Comparable<R>> sort(selector: (Download) -> R, reverse: Boolean) {
        val reordered = downloadManager.queueState.value.groupBy { it.manga.id }.values.flatMap { group ->
            val sorted = group.sortedBy(selector)
            if (reverse) sorted.reversed() else sorted
        }
        downloadManager.reorderQueue(reordered)
    }

    companion object {
        // Long enough to swallow the reorder's clear-then-re-add, short enough to be imperceptible on a
        // real cancel-all.
        private const val TRANSIENT_EMPTY_DEBOUNCE_MS = 150L
    }
}
