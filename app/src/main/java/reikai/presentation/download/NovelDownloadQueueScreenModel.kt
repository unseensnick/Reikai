package reikai.presentation.download

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.injectLazy

/**
 * Backs the novel side of the unified download queue. Aggregates the live per-chapter
 * [NovelDownloadManager.queueState] into one card per novel (title + owning source + a downloaded/total
 * count + a single status), and owns the sticky `All / Manga / Novels` chip selection. Grouping by
 * series is what keeps a full-novel download (thousands of queued chapters) to a single card. The
 * manga side stays on Mihon's own [eu.kanade.tachiyomi.ui.download.DownloadQueueScreenModel]; this is
 * additive.
 */
class NovelDownloadQueueScreenModel :
    StateScreenModel<List<EntryDownloadCardUi>>(emptyList()) {

    private val downloadManager: NovelDownloadManager by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()

    val contentType: StateFlow<ContentType> = sourcePreferences.downloadContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, sourcePreferences.downloadContentType.get())

    // (title, sourceName) per novel, so a burst of progress-driven emissions doesn't re-hit the DB.
    private val metaCache = HashMap<Long, Pair<String, String>>()

    // Highest queued count ever seen per novel: the "total" the card counts down from. Completed
    // chapters leave the queue, so the remaining count alone can't give a stable downloaded/total.
    private val initialTotals = HashMap<Long, Int>()

    init {
        screenModelScope.launchIO {
            combine(
                downloadManager.queueState,
                downloadManager.downloadingNovelId,
            ) { queue, downloadingId -> queue to downloadingId }
                .collectLatest { (queue, downloadingId) ->
                    val byNovel = queue.groupBy { it.novelId }
                    metaCache.keys.retainAll(byNovel.keys)
                    initialTotals.keys.retainAll(byNovel.keys)
                    mutableState.value = byNovel.map { (novelId, downloads) ->
                        val (title, sourceName) = metaCache.getOrPut(novelId) {
                            val novel = novelRepo.getById(novelId)
                            val sourceId = novel?.source.orEmpty()
                            val name = sourceManager.get(sourceId)?.name?.ifBlank { null } ?: sourceId
                            novel?.title.orEmpty() to name
                        }
                        val remaining = downloads.size
                        val total = maxOf(initialTotals[novelId] ?: 0, remaining)
                            .also { initialTotals[novelId] = it }
                        // "Downloading" tracks the manager's latched active novel (not transient per-chapter
                        // state), so it doesn't flicker to "Queued" in the between-chapter pacing gap. Error
                        // shows only when a series is genuinely stuck (every remaining chapter errored), so a
                        // single failed chapter doesn't mislabel a series that's still downloading fine.
                        val status = when {
                            novelId == downloadingId -> EntryDownloadCardStatus.DOWNLOADING
                            downloads.all { it.state == NovelDownload.State.ERROR } -> EntryDownloadCardStatus.ERROR
                            else -> EntryDownloadCardStatus.QUEUED
                        }
                        EntryDownloadCardUi(
                            contentType = ContentType.NOVELS,
                            seriesId = novelId,
                            sourceName = sourceName,
                            title = title,
                            downloadedChapters = (total - remaining).coerceAtLeast(0),
                            totalChapters = total,
                            status = status,
                        )
                    }
                }
        }
    }

    fun setContentType(type: ContentType) = sourcePreferences.downloadContentType.set(type)

    /** Cancel every queued chapter of one novel (the card's cancel action). */
    fun cancelSeries(novelId: Long) =
        downloadManager.cancelDownloads(
            downloadManager.queueState.value.filter { it.novelId == novelId }.map { it.chapterId },
        )

    fun cancelAll() = downloadManager.cancelAllDownloads()

    /** Commit a new series order from a card drag / move-to-top / move-to-bottom: expand each novel
     *  back into its block of queued chapters, in the given order, and persist. Any novel not named in
     *  [order] is appended, so a series enqueued mid-gesture is never dropped. */
    fun reorderBySeries(order: List<Long>) {
        val byNovel = downloadManager.queueState.value.groupBy { it.novelId }
        val named = LinkedHashSet(order)
        val reordered = (order + byNovel.keys.filter { it !in named })
            .flatMap { byNovel[it].orEmpty() }
        downloadManager.reorderQueue(reordered)
    }

    /** Sort each novel's queued chapters among themselves by [selector] (novel order preserved), so the
     *  drain picks them in that order. Runs off the UI thread since it resolves chapter rows. */
    fun <R : Comparable<R>> sort(selector: (NovelChapter) -> R, reverse: Boolean) {
        screenModelScope.launchIO {
            val queue = downloadManager.queueState.value
            val chapterById = queue.associate { it.chapterId to chapterRepo.getById(it.chapterId) }
            val reordered = queue.groupBy { it.novelId }.values.flatMap { group ->
                val sorted = group.sortedBy { chapterById[it.chapterId]?.let(selector) }
                if (reverse) sorted.reversed() else sorted
            }
            downloadManager.reorderQueue(reordered)
        }
    }
}
