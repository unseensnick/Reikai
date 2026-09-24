package reikai.presentation.updates

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.category.RecentsCategoryFilter
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.recents.RECENTS_FEED_LIMIT
import reikai.domain.recents.recentsFeedCutoff
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.toDownloadState
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the light-novel side of the Updates tab, the novel twin of
 * [eu.kanade.tachiyomi.ui.updates.UpdatesViewModel]. Subscribes to the recent-novel-updates feed
 * (chapters fetched after the novel was added) and the download queue, exposing a flat list the
 * shared recents screen groups by date. The chapter verbs live in
 * [reikai.presentation.recents.NovelRecentsChapterActions], which every recents surface builds. Novels
 * rely on the manga tab's unread-count badge reset, so there is nothing to reset here.
 */
@AssistedInject
class NovelUpdatesViewModel(
    // Whose category selection this feed reads: the tab building it, Updates or the combined one.
    @Assisted private val surface: RecentsSurface,
    private val novelRepo: NovelRepository,
    private val downloadManagerProvider: () -> NovelDownloadManager,
    private val novelDownloadCache: NovelDownloadCache,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val updatesPreferences: UpdatesPreferences,
    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo,
) : ViewModel() {

    /** Assisted, so a bare metroViewModel() cannot build this; the surface is the tab's. */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(surface: RecentsSurface): NovelUpdatesViewModel
    }

    // Reuse Mihon's shared updates filter prefs so one toggle filters both manga and novels.
    // Everything the database can answer rides this flow, so a change re-runs the query.
    private fun feedFlow(): Flow<List<NovelUpdateWithRelations>> = combine(
        updatesPreferences.filterUnread.changes(),
        updatesPreferences.filterStarted.changes(),
        updatesPreferences.filterBookmarked.changes(),
        sourcePreferences.recentsCategoryFilterFlow(surface),
    ) { unread, started, bookmarked, categories -> SqlFilters(unread, started, bookmarked, categories) }
        .distinctUntilChanged()
        .flatMapLatest { f ->
            novelRepo.getFilteredNovelUpdatesAsFlow(
                // Recomputed per subscription, like its manga twin.
                after = recentsFeedCutoff(),
                limit = RECENTS_FEED_LIMIT,
                unread = f.unread.toBooleanOrNull(),
                started = f.started.toBooleanOrNull(),
                bookmarked = f.bookmarked.toBooleanOrNull(),
                includedCategories = f.categories.include,
                excludedCategories = f.categories.exclude,
            )
        }

    /**
     * Null until the first query answers, read by the recents updated lane as `loaded`. Novels need no
     * download-override map like the manga twin: progress is not a novel capability, so the queue state
     * rides this combine and a queue change re-derives the rows outright.
     */
    private val updateItems: StateFlow<List<NovelUpdatesItem>?> = combine(
        feedFlow(),
        // Wrapped so the manager is built on first collection, not when this property initializes.
        flow { emitAll(downloadManagerProvider().queueState) },
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
                )
            }
            .filter { applyFilter(filterDownloaded) { it.downloadState == Download.State.DOWNLOADED } }
            // Display-only custom-info overlay, applied last and keyed by the real novel id.
            // Filters and download detection ran on the raw values above.
            .overlayCustomInfo(customInfo)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = updateItems
        .map { State(isLoading = it == null, items = it.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

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

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelUpdatesItem> = emptyList(),
    )
}

@Immutable
data class NovelUpdatesItem(
    val update: NovelUpdateWithRelations,
    val downloadState: Download.State,
)

/** Mirrors the manga model's private conversion; the query wants a nullable Boolean, not a TriState. */
private fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}
