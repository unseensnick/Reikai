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
 * into per-novel groups (resolving the novel title + chapter name for display), and owns the sticky
 * `All / Manga / Novels` chip selection for the queue surface. The manga side stays on Mihon's own
 * [eu.kanade.tachiyomi.ui.download.DownloadQueueScreenModel]; this one is additive.
 */
class NovelDownloadQueueScreenModel :
    StateScreenModel<List<NovelDownloadQueueGroup>>(emptyList()) {

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
                val groups = queue.groupBy { it.novelId }.map { (novelId, downloads) ->
                    val title = titles.getOrPut(novelId) { novelRepo.getById(novelId)?.title.orEmpty() }
                    val items = downloads.map { d ->
                        NovelDownloadQueueItem(
                            chapterId = d.chapterId,
                            chapterName = chapterRepo.getById(d.chapterId)?.name ?: d.url,
                            state = d.state,
                        )
                    }
                    NovelDownloadQueueGroup(novelId, title, items)
                }
                mutableState.value = groups
            }
        }
    }

    fun setContentType(type: ContentType) = sourcePreferences.downloadContentType.set(type)

    fun cancel(chapterId: Long) = downloadManager.cancelDownloads(listOf(chapterId))

    fun cancelAll() = downloadManager.cancelAllDownloads()
}

data class NovelDownloadQueueGroup(
    val novelId: Long,
    val novelTitle: String,
    val items: List<NovelDownloadQueueItem>,
)

data class NovelDownloadQueueItem(
    val chapterId: Long,
    val chapterName: String,
    val state: NovelDownload.State,
)
