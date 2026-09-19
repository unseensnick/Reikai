package reikai.presentation.download

import cafe.adriel.voyager.core.screen.Screen
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

    /** Names of a series' queued chapters, by chapter id. */
    suspend fun chapterNames(seriesId: Long, chapterIds: Collection<Long>): Map<Long, String>

    suspend fun detailsScreen(seriesId: Long): Screen?

    /** Download whole series in this order; a series not named keeps its place after them. */
    fun reorderSeries(seriesIdsInOrder: List<Long>)

    fun cancelSeries(seriesId: Long)

    fun cancelChapter(chapterId: Long)

    /** Move a chapter to the front of its downloader and start it, retrying it if it failed. */
    fun downloadNow(chapterId: Long)

    /** Move a chapter behind the rest of its series; see [withChapterLastInSeries]. */
    fun moveChapterToBottom(chapterId: Long)

    fun cancelAll()

    fun pause()

    fun start()

    suspend fun sort(key: DownloadQueueSortKey, descending: Boolean)
}
