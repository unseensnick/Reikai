package reikai.presentation.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelHistory
import reikai.domain.novel.interactor.RemoveNovelHistory
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * Novel side of the consolidated History tab (the novel twin of
 * [eu.kanade.tachiyomi.ui.history.HistoryViewModel]). Mihon's manga model drives manga rows; this
 * drives novel rows, both rendered by the shared recents screen. The feed is one row per novel, its
 * most-recently-read chapter; the recents engine searches, interleaves and dates it, so what leaves
 * here is the raw list and nothing else.
 */
class NovelHistoryViewModel(
    private val getNovelHistory: GetNovelHistory = Injekt.get(),
    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo = Injekt.get(),
    private val removeNovelHistory: RemoveNovelHistory = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : ViewModel() {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // The category filter is a query parameter, so the subscription re-runs on it. Search is not one:
    // the engine matches the rows it has already been handed, so this feed asks for all of them.
    private val history: StateFlow<List<NovelHistoryWithRelations>?> =
        sourcePreferences.recentsCategoryFilterFlow(RecentsSurface.HISTORY)
            .distinctUntilChanged()
            .flatMapLatest { categories ->
                // Overlay the display-only custom title/cover onto each row, keyed by the real novel id.
                combine(
                    getNovelHistory.subscribe("", categories.include, categories.exclude),
                    getCustomNovelInfo.subscribeAll(),
                ) { history, customInfo ->
                    val overlay = customInfo.associateBy { it.novelId }
                    history.map { row ->
                        val custom = overlay[row.novelId] ?: return@map row
                        row.copy(
                            title = custom.title ?: row.title,
                            coverData = row.coverData.copy(url = custom.thumbnailUrl ?: row.coverData.url),
                        )
                    }
                }
                    .distinctUntilChanged()
                    .catch { error ->
                        logcat(LogPriority.ERROR, error)
                        _events.send(Event.InternalError)
                    }
                    .flowOn(Dispatchers.IO)
            }
            // Null is this feed's "not loaded yet", read by the shared screen and by the recents read
            // lane. Its manga twin seeds the same way and for the same reason.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = history
        .map { State(list = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    /** The latest novel read, which the seam resumes from. Unfiltered, unlike the feed above. */
    suspend fun getLast(): NovelHistoryWithRelations? = getNovelHistory.getLast()

    fun removeFromHistory(history: NovelHistoryWithRelations) {
        viewModelScope.launchIO { removeNovelHistory.await(history) }
    }

    fun removeAllFromHistory(novelId: Long) {
        viewModelScope.launchIO { removeNovelHistory.await(novelId) }
    }

    fun removeAllHistory() {
        viewModelScope.launchIO {
            removeNovelHistory.awaitAll()
            _events.send(Event.HistoryCleared)
        }
    }

    @Immutable
    data class State(
        val list: List<NovelHistoryWithRelations>? = null,
    )

    sealed interface Event {
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
