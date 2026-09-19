package reikai.presentation.download

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType

/**
 * One downloader as the download queue sees it. The manga and novel downloaders stay separate engines
 * running side by side; this is the only seam the queue talks to them through.
 */
interface DownloadQueueProvider {
    val contentType: ContentType

    val snapshots: Flow<DownloadQueueSnapshot>

    val isRunning: Flow<Boolean>

    suspend fun chapterName(seriesId: Long, chapterId: Long): String?

    /** Download whole series in this order; a series not named keeps its place after them. */
    fun reorderSeries(seriesIdsInOrder: List<Long>)

    fun cancelSeries(seriesId: Long)

    fun cancelAll()

    fun pause()

    fun start()

    suspend fun sort(key: DownloadQueueSortKey, descending: Boolean)
}
