package eu.kanade.tachiyomi.ui.history

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
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class HistoryViewModel(
    // RK: per-entry custom title/cover overrides, overlaid on the displayed rows (display-only)
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    // RK: the History tab's category filter, one selection covering both content types.
    private val reikaiSourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : ViewModel() {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // RK: the recents category filter is a query parameter, so the subscription re-runs on it. Search
    //     is not one: the engine matches the rows it already holds, so this feed asks for all of them.
    private val history: StateFlow<List<HistoryWithRelations>?> =
        reikaiSourcePreferences.recentsCategoryFilterFlow(RecentsSurface.HISTORY)
            .distinctUntilChanged()
            .flatMapLatest { categories ->
                // RK: overlay the display-only custom title/cover onto each row, keyed by the real manga id.
                combine(
                    getHistory.subscribe("", categories.include, categories.exclude),
                    getCustomMangaInfo.subscribeAll(),
                ) { history, customInfo ->
                    val overlay = customInfo.associateBy { it.mangaId }
                    history.map { row ->
                        val custom = overlay[row.mangaId] ?: return@map row
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
            // RK: seeded null, where upstream seeds an empty list. Null is this feed's "not loaded yet",
            //     read by the shared screen and by the recents read lane; an empty seed would make both
            //     announce an empty history a tick before the query answers.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = history
        .map { State(list = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    // RK: the latest manga read, which the seam resumes from. Reads the unfiltered query rather than
    //     the rendered feed, so a category filter cannot change what resume opens.
    suspend fun getLast(): HistoryWithRelations? = withIOContext { getHistory.getLast() }

    fun removeFromHistory(history: HistoryWithRelations) {
        viewModelScope.launchIO {
            removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        viewModelScope.launchIO {
            removeHistory.await(mangaId)
        }
    }

    // RK: suspends and answers, where upstream launched and announced itself through an event. The
    //     shared surface clears both content types behind one confirmation, so the message belongs to
    //     the shell that asked, and it is owed the truth about whether the wipe happened.
    suspend fun removeAllHistory(): Boolean = withIOContext { removeHistory.awaitAll() }

    // RK: the state is down to the feed itself. Search, the dialogs and the whole add-from-a-row flow
    // moved to the recents engine, which runs one add sequence for both content types. The rows leave
    // as they are stored: upstream's date-grouped ui model went with the screen that drew it, and the
    // engine dates its own rows over both feeds.
    @Immutable
    data class State(
        val list: List<HistoryWithRelations>? = null,
    )

    sealed interface Event {
        data object InternalError : Event
    }
}
