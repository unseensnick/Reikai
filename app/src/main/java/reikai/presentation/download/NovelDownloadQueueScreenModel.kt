package reikai.presentation.download

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.injectLazy

/**
 * Backs the novel side of the unified download queue. Mirrors the live [NovelDownloadManager.queueState]
 * into one flat, drag-reorderable list (resolving the novel title + chapter name / number / upload date
 * for display and sorting), and owns the sticky `All / Manga / Novels` chip selection for the queue
 * surface. The manga side stays on Mihon's own
 * [eu.kanade.tachiyomi.ui.download.DownloadQueueScreenModel]; this one is additive.
 */
class NovelDownloadQueueScreenModel :
    StateScreenModel<List<NovelDownloadQueueItem>>(emptyList()) {

    private val downloadManager: NovelDownloadManager by injectLazy()
    private val novelRepo: NovelRepository by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()

    val contentType: StateFlow<ContentType> = sourcePreferences.downloadContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, sourcePreferences.downloadContentType.get())

    init {
        screenModelScope.launchIO {
            downloadManager.queueState.collectLatest { queue ->
                val titles = HashMap<Long, String>()
                mutableState.value = queue.map { d ->
                    val title = titles.getOrPut(d.novelId) { novelRepo.getById(d.novelId)?.title.orEmpty() }
                    val chapter = chapterRepo.getById(d.chapterId)
                    NovelDownloadQueueItem(
                        novelId = d.novelId,
                        novelTitle = title,
                        chapterId = d.chapterId,
                        chapterName = chapter?.name ?: d.url,
                        chapterNumber = chapter?.chapterNumber ?: -1.0,
                        dateUpload = chapter?.dateUpload ?: 0L,
                        state = d.state,
                    )
                }
            }
        }
    }

    fun setContentType(type: ContentType) = sourcePreferences.downloadContentType.set(type)

    fun cancel(chapterId: Long) = downloadManager.cancelDownloads(listOf(chapterId))

    fun cancelAll() = downloadManager.cancelAllDownloads()

    /** Apply a drag-to-reorder result given the chapter ids in their new sequence. */
    fun reorder(chapterIdsInOrder: List<Long>) {
        val current = downloadManager.queueState.value
        val byId = current.associateBy { it.chapterId }
        val ids = chapterIdsInOrder.toHashSet()
        // Append anything enqueued after the UI's snapshot (e.g. a new batch added mid-drag) so a
        // reorder never silently drops it.
        val reordered = chapterIdsInOrder.mapNotNull { byId[it] } + current.filter { it.chapterId !in ids }
        downloadManager.reorderQueue(reordered)
    }

    /** Sort each novel's chapters among themselves by [selector] (the novel's first-appearance order is
     *  kept, mirroring the manga per-series sort), then commit the flattened order. */
    fun <R : Comparable<R>> sort(selector: (NovelDownloadQueueItem) -> R, reverse: Boolean) {
        val reordered = state.value.groupBy { it.novelId }.values.flatMap { group ->
            group.sortedBy(selector).let { if (reverse) it.reversed() else it }
        }
        reorder(reordered.map { it.chapterId })
    }
}

data class NovelDownloadQueueItem(
    val novelId: Long,
    val novelTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    val dateUpload: Long,
    val state: NovelDownload.State,
)
