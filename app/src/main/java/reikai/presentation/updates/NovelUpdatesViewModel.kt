package reikai.presentation.updates

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import eu.kanade.core.preference.asState
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import mihon.core.viewmodel.StateViewModel
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.category.RecentsCategoryFilter
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock

/**
 * Drives the light-novel side of the Updates tab, the novel twin of
 * [eu.kanade.tachiyomi.ui.updates.UpdatesViewModel]. Subscribes to the recent-novel-updates feed
 * (chapters fetched after the novel was added) and the download queue, exposing a flat list the
 * shared recents screen groups by date. Chapter-read/bookmark/download actions reuse the
 * novel repos + [NovelDownloadManager]. Novels rely on the manga tab's unread-count badge reset, so
 * there is nothing to reset here.
 */
class NovelUpdatesViewModel(
    private val novelRepo: NovelRepository = Injekt.get(),
    private val chapterRepo: NovelChapterRepository = Injekt.get(),
    private val setNovelReadStatus: SetNovelReadStatus = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
    private val downloadManager: NovelDownloadManager = Injekt.get(),
    private val novelDownloadCache: NovelDownloadCache = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo = Injekt.get(),
    private val libraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
    private val mangaMergeManager: MangaMergeManager = Injekt.get(),
    private val novelMergeManager: NovelMergeManager = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
) : StateViewModel<NovelUpdatesViewModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Timestamp of the last novel library update, for the shared Updates "Last updated" line. */
    val lastUpdated by novelPreferences.novelLibraryUpdateLastTimestamp().asState(viewModelScope)

    /** Sticky All / Manga / Novels chip state for the Updates tab (drives which screen the tab shows). */
    val contentType: StateFlow<ContentType> = sourcePreferences.updatesContentType.changes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, sourcePreferences.updatesContentType.get())

    fun setContentType(type: ContentType) = sourcePreferences.updatesContentType.set(type)

    /** Whether the novel category filter is constraining the feed; drives the shell's filter-icon tint
     *  on chips where manga's own active-filter flag wouldn't reflect a novel-only selection. */
    val hasActiveCategoryFilter: StateFlow<Boolean> =
        sourcePreferences.recentsCategoryFilterFlow(RecentsSurface.UPDATES)
            .map { it.active }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Collapse a series' same-date chapters into one expandable row (display option, both types). */
    val groupBySeries: StateFlow<Boolean> = sourcePreferences.updatesGroupBySeries.changes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, sourcePreferences.updatesGroupBySeries.get())

    // Merge-aware grouping: each favorite's merge-group key (sources of one merged series share a key),
    // so group-by-series collapses a cross-source merged series into one group instead of one per source.
    // Resolved only while grouping is on. Re-resolves on the MEMBERSHIP flow: the keys have come from
    // the merge-group tables since the pref-to-table cutover, but the triggers were left on the retired
    // prefs, which no longer have a runtime writer, so merging or unmerging never refreshed the grouping.
    val mangaSeriesKeys: StateFlow<Map<Long, String>> = combine(
        sourcePreferences.updatesGroupBySeries.changes(),
        mangaMergeManager.membershipChanges(),
    ) { on, _ ->
        if (on) mangaMergeManager.seriesGroupKeys(getFavorites.await().map { it.id }) else emptyMap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val novelSeriesKeys: StateFlow<Map<Long, String>> = combine(
        sourcePreferences.updatesGroupBySeries.changes(),
        novelMergeManager.membershipChanges(),
    ) { on, _ ->
        if (on) novelMergeManager.seriesGroupKeys(novelRepo.getFavorites().map { it.id }) else emptyMap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val selectedChapterIds = HashSet<Long>()

    init {
        viewModelScope.launchIO {
            val after = Clock.System.now()
                .minus(RECENT_MONTHS, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
            // Reuse Mihon's shared updates filter prefs so one toggle filters both manga and novels.
            // Everything the database can answer rides this flow, so a change re-runs the query.
            val feedFlow = combine(
                updatesPreferences.filterUnread.changes(),
                updatesPreferences.filterStarted.changes(),
                updatesPreferences.filterBookmarked.changes(),
                sourcePreferences.recentsCategoryFilterFlow(RecentsSurface.UPDATES),
            ) { unread, started, bookmarked, categories -> SqlFilters(unread, started, bookmarked, categories) }
                .distinctUntilChanged()
                .flatMapLatest { f ->
                    novelRepo.getFilteredNovelUpdatesAsFlow(
                        after = after,
                        limit = LIMIT,
                        unread = f.unread.toBooleanOrNull(),
                        started = f.started.toBooleanOrNull(),
                        bookmarked = f.bookmarked.toBooleanOrNull(),
                        includedCategories = f.categories.include,
                        excludedCategories = f.categories.exclude,
                    )
                }

            combine(
                feedFlow,
                downloadManager.queueState,
                // Downloaded stays a Kotlin filter: download state is on disk, not in the database.
                updatesPreferences.filterDownloaded.changes(),
                getCustomNovelInfo.subscribeAll(),
                // Re-emit when a download/delete changes the disk index so the row icon refreshes.
                novelDownloadCache.changes,
            ) { updates, queue, filterDownloaded, customInfo, _ ->
                val queueById = queue.associate { it.chapterId to it.state.toDownloadState() }
                updates
                    .map { update ->
                        NovelUpdatesItem(
                            update = update,
                            downloadState = queueById[update.chapterId]
                                ?: if (
                                    novelDownloadCache.isChapterDownloaded(
                                        update.source,
                                        update.novelTitle,
                                        update.chapterName,
                                        update.chapterUrl,
                                    )
                                ) {
                                    Download.State.DOWNLOADED
                                } else {
                                    Download.State.NOT_DOWNLOADED
                                },
                            selected = update.chapterId in selectedChapterIds,
                        )
                    }
                    .filter { applyFilter(filterDownloaded) { it.downloadState == Download.State.DOWNLOADED } }
                    // Display-only custom-info overlay, applied last and keyed by the real novel id.
                    // Filters and download detection ran on the raw values above.
                    .overlayCustomInfo(customInfo)
            }.collectLatest { items ->
                mutableState.update { it.copy(isLoading = false, items = items) }
            }
        }
    }

    /** Everything the feed query answers. Scanlator-exclusion is manga-only, so it has no twin here. */
    private data class SqlFilters(
        val unread: TriState,
        val started: TriState,
        val bookmarked: TriState,
        val categories: RecentsCategoryFilter,
    )

    // Overlay the user's custom title/cover onto each row for display, keyed by real novel id.
    private fun List<NovelUpdatesItem>.overlayCustomInfo(customInfo: List<CustomNovelInfo>): List<NovelUpdatesItem> {
        if (customInfo.isEmpty()) return this
        val overlay = customInfo.associateBy { it.novelId }
        return map { item ->
            val custom = overlay[item.update.novelId] ?: return@map item
            item.copy(
                update = item.update.copy(
                    novelTitle = custom.title ?: item.update.novelTitle,
                    coverData = item.update.coverData.copy(url = custom.thumbnailUrl ?: item.update.coverData.url),
                ),
            )
        }
    }

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

    /** Kick off a novel library update; reports back started vs already-running for the snackbar. */
    fun updateLibrary(): Boolean {
        val started = NovelUpdateJob.startNow(Injekt.get<Application>())
        viewModelScope.launch {
            _events.send(Event.LibraryUpdateTriggered(started))
        }
        return started
    }

    fun markRead(items: List<NovelUpdatesItem>, read: Boolean) {
        viewModelScope.launchIO {
            // Route through the shared read interactor so mark-read here also deletes downloads when
            // "delete after read" is on, matching manga (and the novel details/reader/library paths).
            val chapters = items.mapNotNull { chapterRepo.getById(it.update.chapterId) }
            setNovelReadStatus.await(read, chapters)
            selectAll(false)
        }
    }

    fun bookmark(items: List<NovelUpdatesItem>, bookmark: Boolean) {
        viewModelScope.launchIO {
            items.forEach { chapterRepo.setBookmark(it.update.chapterId, bookmark) }
            selectAll(false)
        }
    }

    fun downloadChapters(items: List<NovelUpdatesItem>) {
        viewModelScope.launchIO {
            val chapters = items.mapNotNull { chapterRepo.getById(it.update.chapterId) }
            if (chapters.isNotEmpty()) downloadManager.downloadChapters(chapters)
            selectAll(false)
        }
    }

    fun deleteChapters(items: List<NovelUpdatesItem>) {
        viewModelScope.launchIO {
            val chapters = items.mapNotNull { chapterRepo.getById(it.update.chapterId) }
            if (chapters.isNotEmpty()) downloadManager.deleteChapters(chapters)
            selectAll(false)
        }
    }

    /** Per-row download icon, mirroring the novel details download-action mapping. */
    fun onDownloadAction(item: NovelUpdatesItem, action: ChapterDownloadAction) {
        viewModelScope.launchIO {
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

    sealed interface Event {
        data class LibraryUpdateTriggered(val started: Boolean) : Event
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

/** Mirrors the manga model's private conversion; the query wants a nullable Boolean, not a TriState. */
private fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}
