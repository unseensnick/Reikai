package reikai.presentation.download

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
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

    val state: StateFlow<State> = combine(
        mangaProvider.snapshots,
        novelProvider.snapshots,
        sourcePreferences.downloadQueueOrder.changes(),
    ) { manga, novels, savedOrder -> Triple(manga, novels, savedOrder) }
        .mapLatest { (manga, novels, savedOrder) ->
            val cardsByType = mapOf(
                ContentType.MANGA to manga.toCards(ContentType.MANGA),
                ContentType.NOVELS to novels.toCards(ContentType.NOVELS),
            )
            State(arrangeCards(savedOrder.toKeys(), cardsByType).map { it.withChapterName() })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    val isRunning: StateFlow<Map<ContentType, Boolean>> = combine(
        mangaProvider.isRunning,
        novelProvider.isRunning,
    ) { manga, novels -> mapOf(ContentType.MANGA to manga, ContentType.NOVELS to novels) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), emptyMap())

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
        // Forgotten here so the series lands at the end, not in its old place, if it is queued again.
        val saved = sourcePreferences.downloadQueueOrder.get().toKeys()
        sourcePreferences.downloadQueueOrder.set((saved - card.cardKey).joinToString(ORDER_SEPARATOR))
    }

    fun cancelAll() {
        providers.values.forEach { it.cancelAll() }
        sourcePreferences.downloadQueueOrder.set("")
    }

    /** Pause every running downloader, or start every one with something queued. */
    fun togglePause() {
        val running = isRunning.value
        if (running.values.any { it }) {
            providers.filterKeys { running[it] == true }.values.forEach { it.pause() }
        } else {
            val queuedTypes = state.value.cards.mapTo(HashSet()) { it.contentType }
            providers.filterKeys { it in queuedTypes }.values.forEach { it.start() }
        }
    }

    fun sort(key: DownloadQueueSortKey, descending: Boolean) {
        viewModelScope.launchIO { providers.values.forEach { it.sort(key, descending) } }
    }

    private suspend fun EntryDownloadCardUi.withChapterName(): EntryDownloadCardUi {
        val chapterId = currentChapterId ?: return this
        return copy(currentChapterName = providers[contentType]?.chapterName(seriesId, chapterId))
    }

    private fun String.toKeys(): List<String> = split(ORDER_SEPARATOR).filter { it.isNotEmpty() }

    @Immutable
    data class State(val cards: List<EntryDownloadCardUi> = emptyList()) {
        /** Chapters still to download across every card. */
        val pendingChapters: Int get() = cards.sumOf { it.totalChapters - it.downloadedChapters }

        /** The type badge only matters while both content types are queued. */
        val showTypeBadge: Boolean get() = cards.distinctBy { it.contentType }.size > 1
    }

    private companion object {
        const val ORDER_SEPARATOR = ","
    }
}
