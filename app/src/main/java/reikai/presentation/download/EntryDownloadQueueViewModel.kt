package reikai.presentation.download

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

/**
 * The download queue for both content types: one list in one saved order over the two downloaders,
 * which keep running side by side. Moving a card up makes it next in its own downloader, and the
 * list shows each downloader's real order (see [arrangeCards]).
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class EntryDownloadQueueViewModel(
    mangaProvider: MangaDownloadQueueProvider,
    novelProvider: NovelDownloadQueueProvider,
    private val sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    private val providers: Map<ContentType, DownloadQueueProvider> =
        listOf(mangaProvider, novelProvider).associateBy { it.contentType }

    private val snapshots: Flow<Map<ContentType, DownloadQueueSnapshot>> = combine(
        mangaProvider.snapshots,
        novelProvider.snapshots,
    ) { manga, novels -> mapOf(ContentType.MANGA to manga, ContentType.NOVELS to novels) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), replay = 1)

    val state: StateFlow<State> = combine(
        snapshots,
        sourcePreferences.downloadQueueOrder.changes(),
    ) { snapshotsByType, savedOrder -> snapshotsByType to savedOrder }
        .mapLatest { (snapshotsByType, savedOrder) ->
            val cardsByType = snapshotsByType.mapValues { (type, snapshot) -> snapshot.toCards(type) }
            val saved = savedOrder.toKeys()
            val kept = prunedOrder(saved, cardsByType)
            if (kept.size != saved.size) sourcePreferences.downloadQueueOrder.set(kept.joinToString(ORDER_SEPARATOR))
            State(arrangeCards(kept, cardsByType).map { it.withChapterName() })
        }
        // The whole queue is rebuilt on every status change and progress sample; keep that off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private val openedSeries = MutableStateFlow<Pair<ContentType, Long>?>(null)

    /** The chapter sheet of the series the user opened; null once that series has left the queue. */
    val sheet: StateFlow<SeriesSheet?> = combine(snapshots, openedSeries) { snapshotsByType, opened ->
        snapshotsByType to opened
    }
        .mapLatest { (snapshotsByType, opened) ->
            val (type, seriesId) = opened ?: return@mapLatest null
            val snapshot = snapshotsByType[type] ?: return@mapLatest null
            val card = snapshot.toCards(type).find { it.seriesId == seriesId }
            if (card == null) {
                openedSeries.value = null
                return@mapLatest null
            }
            val chapters = snapshot.chapters.filter { it.seriesId == seriesId }
            val names = providers[type]?.chapterNames(seriesId, chapters.map { it.chapterId }).orEmpty()
            SeriesSheet(card, chapters.map { EntryDownloadChapterUi(it, names[it.chapterId].orEmpty()) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    private val isRunning: StateFlow<Map<ContentType, Boolean>> = combine(
        mangaProvider.isRunning,
        novelProvider.isRunning,
    ) { manga, novels -> mapOf(ContentType.MANGA to manga, ContentType.NOVELS to novels) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), emptyMap())

    /** The same answer the More row gives, so the pause button and that row never disagree. */
    val queueState: StateFlow<DownloadQueueState> = combine(snapshots, isRunning) { snapshotsByType, running ->
        downloadQueueState(
            snapshotsByType.map { (type, snapshot) ->
                EngineQueueStatus(snapshot.chapters.size, running[type] == true)
            },
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), DownloadQueueState.Stopped)

    /** Commit a new card order: save it, then hand each downloader its share where that changed. */
    fun reorder(cardKeysInOrder: List<String>) {
        val cards = state.value.cards
        val known = cards.mapTo(HashSet()) { it.cardKey }
        sourcePreferences.downloadQueueOrder.set(cardKeysInOrder.filter { it in known }.joinToString(ORDER_SEPARATOR))
        seriesOrderChanges(cards, cardKeysInOrder).forEach { (type, seriesIds) ->
            providers[type]?.reorderSeries(seriesIds)
        }
    }

    fun cancel(card: EntryDownloadCardUi) {
        providers[card.contentType]?.cancelSeries(card.seriesId)
    }

    fun cancelAll() {
        providers.values.forEach { it.cancelAll() }
        sourcePreferences.downloadQueueOrder.set("")
    }

    /** Pause every running downloader, or start every one with something queued. */
    fun togglePause() {
        val running = isRunning.value
        if (queueState.value is DownloadQueueState.Downloading) {
            providers.filterKeys { running[it] == true }.values.forEach { it.pause() }
        } else {
            val queuedTypes = state.value.cards.mapTo(HashSet()) { it.contentType }
            providers.filterKeys { it in queuedTypes }.values.forEach { it.start() }
        }
    }

    fun sort(key: DownloadQueueSortKey, descending: Boolean) {
        viewModelScope.launchIO { providers.values.forEach { it.sort(key, descending) } }
    }

    fun openSeries(card: EntryDownloadCardUi) {
        openedSeries.value = card.contentType to card.seriesId
    }

    fun closeSeries() {
        openedSeries.value = null
    }

    fun cancelChapter(type: ContentType, chapterId: Long) {
        providers[type]?.cancelChapter(chapterId)
    }

    fun downloadNow(type: ContentType, chapterId: Long) {
        providers[type]?.downloadNow(chapterId)
    }

    fun moveChapterToBottom(type: ContentType, chapterId: Long) {
        providers[type]?.moveChapterToBottom(chapterId)
    }

    suspend fun detailsScreen(card: EntryDownloadCardUi): Screen? =
        providers[card.contentType]?.detailsScreen(card.seriesId)

    private suspend fun EntryDownloadCardUi.withChapterName(): EntryDownloadCardUi {
        val chapterId = currentChapterId ?: return this
        val names = providers[contentType]?.chapterNames(seriesId, listOf(chapterId)).orEmpty()
        return copy(currentChapterName = names[chapterId])
    }

    private fun String.toKeys(): List<String> = split(ORDER_SEPARATOR).filter { it.isNotEmpty() }

    @Immutable
    data class State(val cards: List<EntryDownloadCardUi> = emptyList()) {
        /** Chapters still to download across every card. */
        val pendingChapters: Int get() = cards.sumOf { it.totalChapters - it.downloadedChapters }

        /** The type badge only matters while both content types are queued. */
        val showTypeBadge: Boolean get() = cards.distinctBy { it.contentType }.size > 1
    }

    @Immutable
    data class SeriesSheet(val card: EntryDownloadCardUi, val chapters: List<EntryDownloadChapterUi>)

    private companion object {
        const val ORDER_SEPARATOR = ","
    }
}
