package reikai.presentation.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType

/**
 * One downloader as the download queue sees it. The manga and novel downloaders stay separate engines
 * running side by side; this is the only seam the queue talks to them through.
 */
interface DownloadQueueProvider {
    val contentType: ContentType

    val snapshots: Flow<DownloadQueueSnapshot>

    val isRunning: Flow<Boolean>

    /** Names of a series' queued chapters, by chapter id. */
    suspend fun chapterNames(seriesId: Long, chapterIds: Collection<Long>): Map<Long, String>

    /** Download whole series in this order; a series not named keeps its place after them. */
    suspend fun reorderSeries(seriesIdsInOrder: List<Long>)

    suspend fun cancelSeries(seriesId: Long)

    suspend fun cancelChapter(chapterId: Long)

    /** Move a chapter to the front of its downloader and start it, retrying it if it failed. */
    suspend fun downloadNow(chapterId: Long)

    /** Move a chapter behind the rest of its series; see [withChapterLastInSeries]. */
    suspend fun moveChapterToBottom(chapterId: Long)

    suspend fun cancelAll()

    suspend fun pause()

    fun start()

    suspend fun sort(key: DownloadQueueSortKey, descending: Boolean)
}

/**
 * The queue a snapshot draws and whether the saved queue was restored yet. The flag is read before the
 * live queue, never the queue an emission carried, which may predate the restore: read the other way, a
 * restore landing between the two reads pairs the old empty queue with a finished restore.
 */
internal fun <Q> readQueueAfterRestore(isQueueRestored: () -> Boolean, queue: StateFlow<Q>): Pair<Q, Boolean> {
    val restored = isQueueRestored()
    return queue.value to restored
}
