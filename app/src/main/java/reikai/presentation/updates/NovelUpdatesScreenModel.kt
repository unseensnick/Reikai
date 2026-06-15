package reikai.presentation.updates

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime

/**
 * Drives the light-novel side of the Updates tab, the novel twin of
 * [eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel]. Subscribes to the recent-novel-updates feed
 * (chapters fetched after the novel was added) and the download queue, exposing a flat list the
 * combined [ReikaiUpdatesScreen] groups by date. Chapter-read/bookmark/download actions reuse the
 * novel repos + [NovelDownloadManager]. The unread-count badge is reset by the manga screen model on
 * tab open, so there is nothing to reset here.
 */
class NovelUpdatesScreenModel(
    private val novelRepo: NovelRepository = Injekt.get(),
    private val chapterRepo: NovelChapterRepository = Injekt.get(),
    private val downloadManager: NovelDownloadManager = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
) : StateScreenModel<NovelUpdatesScreenModel.State>(State()) {

    /** Sticky All / Manga / Novels chip state for the Updates tab (drives which screen the tab shows). */
    val contentType: StateFlow<ContentType> = sourcePreferences.updatesContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, sourcePreferences.updatesContentType.get())

    fun setContentType(type: ContentType) = sourcePreferences.updatesContentType.set(type)

    private val selectedChapterIds = HashSet<Long>()

    init {
        screenModelScope.launchIO {
            val after = ZonedDateTime.now().minusMonths(RECENT_MONTHS).toInstant().toEpochMilli()
            // Reuse Mihon's shared updates filter prefs so one toggle filters both manga and novels.
            val filterFlow = combine(
                updatesPreferences.filterUnread.changes(),
                updatesPreferences.filterDownloaded.changes(),
                updatesPreferences.filterStarted.changes(),
                updatesPreferences.filterBookmarked.changes(),
            ) { unread, downloaded, started, bookmarked -> Filters(unread, downloaded, started, bookmarked) }

            combine(
                novelRepo.getRecentNovelUpdatesAsFlow(after, LIMIT),
                downloadManager.queueState,
                filterFlow,
            ) { updates, queue, filters ->
                val queueById = queue.associate { it.chapterId to it.state.toDownloadState() }
                updates
                    .map { update ->
                        NovelUpdatesItem(
                            update = update,
                            downloadState = queueById[update.chapterId]
                                ?: if (update.isDownloaded) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED,
                            selected = update.chapterId in selectedChapterIds,
                        )
                    }
                    .filter { it.matchesFilters(filters) }
            }.collectLatest { items ->
                mutableState.update { it.copy(isLoading = false, items = items) }
            }
        }
    }

    /** The 4 shared filters; scanlator-exclusion is manga-only so it has no novel equivalent. */
    private data class Filters(
        val unread: TriState,
        val downloaded: TriState,
        val started: TriState,
        val bookmarked: TriState,
    )

    private fun NovelUpdatesItem.matchesFilters(f: Filters): Boolean =
        applyFilter(f.unread) { !update.read } &&
            applyFilter(f.downloaded) { downloadState == Download.State.DOWNLOADED } &&
            applyFilter(f.started) { update.lastTextProgress > 0L && !update.read } &&
            applyFilter(f.bookmarked) { update.bookmark }

    fun toggleSelection(chapterId: Long, selected: Boolean) {
        if (selected) selectedChapterIds.add(chapterId) else selectedChapterIds.remove(chapterId)
        refreshSelection()
    }

    fun selectAll(selected: Boolean) {
        selectedChapterIds.clear()
        if (selected) selectedChapterIds.addAll(state.value.items.map { it.update.chapterId })
        refreshSelection()
    }

    fun invertSelection() {
        val inverted = state.value.items.map { it.update.chapterId }.filterNot { it in selectedChapterIds }
        selectedChapterIds.clear()
        selectedChapterIds.addAll(inverted)
        refreshSelection()
    }

    private fun refreshSelection() {
        mutableState.update { st ->
            st.copy(items = st.items.map { it.copy(selected = it.update.chapterId in selectedChapterIds) })
        }
    }

    fun markRead(items: List<NovelUpdatesItem>, read: Boolean) {
        screenModelScope.launchIO {
            chapterRepo.setReadBulk(items.map { it.update.chapterId }, read)
            selectAll(false)
        }
    }

    fun bookmark(items: List<NovelUpdatesItem>, bookmark: Boolean) {
        screenModelScope.launchIO {
            items.forEach { chapterRepo.setBookmark(it.update.chapterId, bookmark) }
            selectAll(false)
        }
    }

    fun downloadChapters(items: List<NovelUpdatesItem>) {
        screenModelScope.launchIO {
            val chapters = items.mapNotNull { chapterRepo.getById(it.update.chapterId) }
            if (chapters.isNotEmpty()) downloadManager.downloadChapters(chapters)
            selectAll(false)
        }
    }

    fun deleteChapters(items: List<NovelUpdatesItem>) {
        screenModelScope.launchIO {
            val chapters = items.mapNotNull { chapterRepo.getById(it.update.chapterId) }
            if (chapters.isNotEmpty()) downloadManager.deleteChapters(chapters)
            selectAll(false)
        }
    }

    /** Per-row download icon, mirroring the novel details download-action mapping. */
    fun onDownloadAction(item: NovelUpdatesItem, action: ChapterDownloadAction) {
        screenModelScope.launchIO {
            val chapter = chapterRepo.getById(item.update.chapterId) ?: return@launchIO
            when (action) {
                ChapterDownloadAction.START -> downloadManager.downloadChapters(listOf(chapter))
                ChapterDownloadAction.START_NOW -> {
                    downloadManager.downloadChapters(listOf(chapter))
                    downloadManager.startDownloadNow(chapter.id)
                }
                ChapterDownloadAction.CANCEL -> downloadManager.cancelDownloads(listOf(chapter.id))
                ChapterDownloadAction.DELETE -> downloadManager.deleteChapters(listOf(chapter))
            }
        }
    }

    private fun NovelDownload.State.toDownloadState(): Download.State = when (this) {
        NovelDownload.State.QUEUE -> Download.State.QUEUE
        NovelDownload.State.DOWNLOADING -> Download.State.DOWNLOADING
        NovelDownload.State.ERROR -> Download.State.ERROR
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelUpdatesItem> = emptyList(),
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()
    }

    companion object {
        private const val RECENT_MONTHS = 3L
        private const val LIMIT = 500L
    }
}

@Immutable
data class NovelUpdatesItem(
    val update: NovelUpdateWithRelations,
    val downloadState: Download.State,
    val selected: Boolean = false,
)
