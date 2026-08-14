package eu.kanade.tachiyomi.ui.updates

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import logcat.LogPriority
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock

class UpdatesViewModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    // RK: per-entry custom title/cover overrides, overlaid on the displayed rows (display-only)
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    // RK: the Updates tab's category filter, one selection covering both content types, applied in SQL.
    private val reikaiSourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : ViewModel() {

    val state: StateFlow<UpdatesViewModel.State>
        field = MutableStateFlow<UpdatesViewModel.State>(State())

    init {
        viewModelScope.launchIO {
            // Set date limit for recent chapters
            val limit = Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())

            combine(
                // needed for SQL filters (unread, started, bookmarked, etc)
                // RK: the category selection is a query parameter, so it rides the same flow the
                //     subscription re-runs on. Re-categorizing a series now reflects without reopening.
                combine(
                    getUpdatesItemPreferenceFlow(),
                    reikaiSourcePreferences.recentsCategoryFilterFlow(RecentsSurface.UPDATES),
                    ::Pair,
                )
                    .distinctUntilChanged()
                    .flatMapLatest { (prefs, categories) ->
                        getUpdates.subscribe(
                            limit,
                            unread = prefs.filterUnread.toBooleanOrNull(),
                            started = prefs.filterStarted.toBooleanOrNull(),
                            bookmarked = prefs.filterBookmarked.toBooleanOrNull(),
                            hideExcludedScanlators = prefs.filterExcludedScanlators,
                            includedCategories = categories.include,
                            excludedCategories = categories.exclude,
                        ).distinctUntilChanged()
                    },
                downloadCache.changes,
                downloadManager.queueState,
                // needed for Kotlin filters (downloaded)
                getUpdatesItemPreferenceFlow().distinctUntilChanged { old, new ->
                    old.filterDownloaded == new.filterDownloaded
                },
                // RK: display-only custom-info overlay, applied last and keyed by the real manga id.
                //     Filters and download detection ran on the raw title; only the displayed
                //     title/cover carry the user's overrides.
                getCustomMangaInfo.subscribeAll(),
            ) { updates, _, _, itemPreferences, customInfo ->
                updates
                    .toUpdateItems()
                    .applyFilters(itemPreferences)
                    .overlayCustomInfo(customInfo)
            }
                .collectLatest { updateItems ->
                    state.update {
                        it.copy(
                            isLoading = false,
                            items = updateItems,
                        )
                    }
                }
        }

        viewModelScope.launchIO {
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(this@UpdatesViewModel::updateDownloadState)
        }
    }

    private fun List<UpdatesItem>.applyFilters(
        preferences: ItemPreferences,
    ): List<UpdatesItem> {
        val filterDownloaded = preferences.filterDownloaded

        val filterFnDownloaded: (UpdatesItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.downloadStateProvider() == Download.State.DOWNLOADED
            }
        }

        return fastFilter {
            filterFnDownloaded(it)
        }
    }

    // RK --> overlay the user's custom title/cover onto each row for display, keyed by real manga id.
    private fun List<UpdatesItem>.overlayCustomInfo(customInfo: List<CustomMangaInfo>): List<UpdatesItem> {
        if (customInfo.isEmpty()) return this
        val overlay = customInfo.associateBy { it.mangaId }
        return map { item ->
            val custom = overlay[item.update.mangaId] ?: return@map item
            item.copy(
                update = item.update.copy(
                    mangaTitle = custom.title ?: item.update.mangaTitle,
                    coverData = item.update.coverData.copy(url = custom.thumbnailUrl ?: item.update.coverData.url),
                ),
            )
        }
    }
    // RK <--

    private fun List<UpdatesWithRelations>.toUpdateItems(): List<UpdatesItem> {
        return this
            .map { update ->
                val activeDownload = downloadManager.getQueuedDownloadOrNull(update.chapterId)
                val downloaded = downloadManager.isChapterDownloaded(
                    update.chapterName,
                    update.scanlator,
                    update.chapterUrl,
                    update.mangaTitle,
                    update.sourceId,
                )
                val downloadState = when {
                    activeDownload != null -> activeDownload.status
                    downloaded -> Download.State.DOWNLOADED
                    else -> Download.State.NOT_DOWNLOADED
                }
                UpdatesItem(
                    update = update,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { activeDownload?.progress ?: 0 },
                )
            }
    }

    /**
     * Update status of chapters.
     *
     * @param download download object containing progress.
     */
    private fun updateDownloadState(download: Download) {
        state.update { state ->
            val newItems = state.items.toMutableList().also { list ->
                val modifiedIndex = list.indexOfFirst { it.update.chapterId == download.chapter.id }
                if (modifiedIndex < 0) return@also

                val item = list[modifiedIndex]
                list[modifiedIndex] = item.copy(
                    downloadStateProvider = { download.status },
                    downloadProgressProvider = { download.progress },
                )
            }
            state.copy(items = newItems)
        }
    }

    // RK --> The four chapter verbs take chapter ids rather than rendered rows. Recents dispatches
    // over a mixed feed whose read-lane rows have no UpdatesItem to look one up by, and every verb
    // already re-read the chapter from that id, so the row was only ever indirection.
    fun downloadChapters(chapterIds: List<Long>, action: ChapterDownloadAction) {
        if (chapterIds.isEmpty()) return
        viewModelScope.launch {
            when (action) {
                ChapterDownloadAction.START -> {
                    downloadChapters(chapterIds)
                    if (anyDownloadFailed(chapterIds)) {
                        downloadManager.startDownloads()
                    }
                }
                ChapterDownloadAction.START_NOW -> startDownloadingNow(chapterIds.singleOrNull() ?: return@launch)
                ChapterDownloadAction.CANCEL -> cancelDownload(chapterIds.singleOrNull() ?: return@launch)
                ChapterDownloadAction.DELETE -> deleteChapters(chapterIds)
            }
        }
    }

    /** Upstream read this off the row's own state provider; the queue is where that value came from. */
    private fun anyDownloadFailed(chapterIds: List<Long>): Boolean =
        chapterIds.any { downloadManager.getQueuedDownloadOrNull(it)?.status == Download.State.ERROR }
    // RK <--

    private fun startDownloadingNow(chapterId: Long) {
        downloadManager.startDownloadNow(chapterId)
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    /**
     * Mark the given chapters as read/unread.
     * @param chapterIds the chapters to mark.
     * @param read whether to mark chapters as read or unread.
     */
    // RK: keyed by chapter id, see the download island above.
    fun markUpdatesRead(chapterIds: List<Long>, read: Boolean) {
        viewModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapterIds
                    .mapNotNull { getChapter.await(it) }
                    .toTypedArray(),
            )
        }
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapterIds the chapters to bookmark.
     */
    // RK: keyed by chapter id. The already-at-this-value skip reads the stored chapter rather than a
    // rendered row's copy of it, which is the only value a read-lane row could not have supplied.
    fun bookmarkUpdates(chapterIds: List<Long>, bookmark: Boolean) {
        viewModelScope.launchIO {
            chapterIds
                .mapNotNull { getChapter.await(it) }
                .filterNot { it.bookmark == bookmark }
                .map { ChapterUpdate(id = it.id, bookmark = bookmark) }
                .let { updateChapter.awaitAll(it) }
        }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapterIds the chapters to download.
     */
    // RK: keyed by chapter id, so the manga is resolved from the chapter rather than from the row.
    private fun downloadChapters(chapterIds: List<Long>) {
        viewModelScope.launchNonCancellable {
            chapterIds
                .mapNotNull { getChapter.await(it) }
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    val manga = getManga.await(mangaId) ?: return@forEach
                    // Don't download if source isn't available
                    sourceManager.get(manga.source) ?: return@forEach
                    downloadManager.downloadChapters(manga, chapters)
                }
        }
    }

    /**
     * Delete selected chapters
     *
     * @param chapterIds list of chapters
     */
    // RK: keyed by chapter id, see above.
    fun deleteChapters(chapterIds: List<Long>) {
        viewModelScope.launchNonCancellable {
            chapterIds
                .mapNotNull { getChapter.await(it) }
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    val manga = getManga.await(mangaId) ?: return@forEach
                    val source = sourceManager.get(manga.source) ?: return@forEach
                    downloadManager.deleteChapters(chapters, manga, source)
                }
        }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount.set(0)
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterDownloaded.changes(),
            updatesPreferences.filterUnread.changes(),
            updatesPreferences.filterStarted.changes(),
            updatesPreferences.filterBookmarked.changes(),
            updatesPreferences.filterExcludedScanlators.changes(),
        ) { downloaded, unread, started, bookmarked, excludedScanlators ->
            ItemPreferences(
                filterDownloaded = downloaded,
                filterUnread = unread,
                filterStarted = started,
                filterBookmarked = bookmarked,
                filterExcludedScanlators = excludedScanlators,
            )
        }
    }

    @Immutable
    private data class ItemPreferences(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterExcludedScanlators: Boolean,
    )

    // RK: the state is down to the feed itself. Selection, the dialogs, the active-filter flag and the
    // last-updated line moved to the recents engine, which owns them for both content types; upstream's
    // getUiModel() went with the Mihon screen it fed.
    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<UpdatesItem> = listOf(),
    )
}

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}

@Immutable
data class UpdatesItem(
    val update: UpdatesWithRelations,
    val downloadStateProvider: () -> Download.State,
    val downloadProgressProvider: () -> Int,
)
