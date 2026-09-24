package eu.kanade.tachiyomi.ui.updates

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import logcat.LogPriority
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class UpdatesViewModel(
    // RK: no chapter verbs and none of their interactors. They moved to MangaRecentsChapterActions,
    //     which every recents surface builds, History included.
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val getUpdates: GetUpdates,
    // RK: per-entry custom title/cover overrides, overlaid on the displayed rows (display-only)
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val libraryPreferences: LibraryPreferences,
    private val updatesPreferences: UpdatesPreferences,
    // RK: the Updates tab's category filter, one selection covering both content types, applied in SQL.
    private val reikaiSourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    /**
     * Live download progress, held beside the feed rather than patched into it. The feed is derived
     * now, so a status tick has nowhere to write; keeping it separate also drops an override once the
     * download reaches a terminal state, instead of letting it outlive the row it described.
     */
    private val downloadStates = MutableStateFlow(emptyMap</* Chapter */ Long, DownloadProgress>())

    private val updateItems: StateFlow<List<UpdatesItem>?> = combine(
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
                    // Recomputed per subscription, so a long-running process keeps a three month
                    // window from now rather than from whenever the model was built.
                    Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault()),
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
        .flowOn(Dispatchers.IO)
        // RK: seeded null for the same reason the history feeds are, and read the same way: the
        //     recents updated lane turns it into `loaded`, so an empty seed would announce an empty
        //     Updates tab a tick before the query answers.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(updateItems, downloadStates) { items, downloads ->
        State(
            isLoading = items == null,
            items = items.orEmpty().applyDownloadOverrides(downloads),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    init {
        viewModelScope.launchIO {
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(this@UpdatesViewModel::updateDownloadState)
        }
        // RK --> drop an override once its chapter leaves the queue. Upstream's cancel verb patched this
        //     map itself, because the cancel's own status tick can be lost as the queue re-emits; that
        //     verb now lives outside this model, so the queue clears it instead.
        viewModelScope.launchIO {
            downloadManager.queueState.collect { queue ->
                val queued = queue.mapTo(HashSet()) { it.chapter.id }
                downloadStates.update { states -> states.filterKeys(queued::contains) }
            }
        }
        // RK <--
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

    // RK --> overlay live queue progress onto the queried rows, which only know what the disk index
    // said. Upstream merges this inline in its state combine, where it also stamps each row's
    // `selected`; selection lives on the recents engine here, so the merge is the whole job and reads
    // better beside the other row overlay. The empty guard matters: progress ticks are frequent.
    private fun List<UpdatesItem>.applyDownloadOverrides(
        downloads: Map<Long, DownloadProgress>,
    ): List<UpdatesItem> {
        if (downloads.isEmpty()) return this
        return map { item ->
            val download = downloads[item.update.chapterId] ?: return@map item
            item.copy(
                downloadStateProvider = { download.status },
                downloadProgressProvider = { download.progress },
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
        val chapterId = download.chapter.id
        downloadStates.update {
            // A terminal state is what the queried row already answers, so drop the override rather
            // than let it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to DownloadProgress(download.status, download.progress))
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

    private data class DownloadProgress(val status: Download.State, val progress: Int)

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
