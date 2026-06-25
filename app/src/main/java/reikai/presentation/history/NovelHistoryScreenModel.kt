package reikai.presentation.history

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.GetNovelHistory
import reikai.domain.novel.interactor.RemoveNovelHistory
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Novel side of the consolidated History tab (the novel twin of
 * [eu.kanade.tachiyomi.ui.history.HistoryScreenModel]). Mihon's manga model drives manga rows; this
 * drives novel rows, both rendered by [ReikaiHistoryScreen]. The feed is one row per novel (its
 * most-recently-read chapter), searchable by title; the consolidated screen interleaves it with the
 * manga feed and inserts the date headers, so the raw list is exposed here (no per-model UI model).
 */
class NovelHistoryScreenModel(
    private val getNovelHistory: GetNovelHistory = Injekt.get(),
    private val removeNovelHistory: RemoveNovelHistory = Injekt.get(),
    private val getNextNovelChapter: GetNextNovelChapter = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val novelLibraryAdder: NovelLibraryAdder = Injekt.get(),
) : StateScreenModel<NovelHistoryScreenModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Shared All / Manga / Novels chip, persisted under its own key (mirrors the Updates tab). */
    val contentType: StateFlow<ContentType> = sourcePreferences.historyContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, sourcePreferences.historyContentType.get())

    fun setContentType(type: ContentType) = sourcePreferences.historyContentType.set(type)

    init {
        screenModelScope.launch {
            state.map { it.searchQuery }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    getNovelHistory.subscribe(query ?: "")
                        .distinctUntilChanged()
                        .catch { error ->
                            logcat(LogPriority.ERROR, error)
                            _events.send(Event.InternalError)
                        }
                        .flowOn(Dispatchers.IO)
                }
                .collect { newList -> mutableState.update { it.copy(list = newList) } }
        }
    }

    /** Resume a history row: reopen the recorded chapter if unread, else the next one (null = none left). */
    fun resume(history: NovelHistoryWithRelations) {
        screenModelScope.launchIO {
            val next = getNextNovelChapter.await(history.novelId, history.chapterId)
            _events.send(Event.OpenChapter(history.novelId, next?.id))
        }
    }

    /** Open a novel's details. NovelScreen is keyed by (source, url), so resolve them from the id here. */
    fun openDetails(novelId: Long) {
        screenModelScope.launchIO {
            val novel = novelRepository.getById(novelId) ?: return@launchIO
            _events.send(Event.OpenNovel(novel.source, novel.url))
        }
    }

    /** Add a not-yet-library novel from its history row: favorite the existing row, then apply the
     *  default category or prompt (reuses NovelLibraryAdder's add-to-library category logic). */
    fun addFavorite(novelId: Long) {
        screenModelScope.launchIO {
            updateNovel.awaitUpdateFavorite(novelId, favorite = true)
            novelLibraryAdder.applyDefaultCategoryOrPrompt(novelId)?.let { prompt ->
                setDialog(Dialog.ChangeCategory(novelId, prompt.categories, prompt.currentIds))
            }
        }
    }

    fun applyCategories(novelId: Long, categoryIds: List<Long>) {
        setDialog(null)
        screenModelScope.launchIO { novelLibraryAdder.applyCategories(novelId, categoryIds) }
    }

    /** The latest novel read, for the tab-reselect global-latest resume. */
    suspend fun getLast(): NovelHistoryWithRelations? = getNovelHistory.getLast()

    fun removeFromHistory(history: NovelHistoryWithRelations) {
        screenModelScope.launchIO { removeNovelHistory.await(history) }
    }

    fun removeAllFromHistory(novelId: Long) {
        screenModelScope.launchIO { removeNovelHistory.await(novelId) }
    }

    fun removeAllHistory() {
        screenModelScope.launchIO {
            removeNovelHistory.awaitAll()
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) = mutableState.update { it.copy(searchQuery = query) }

    fun setDialog(dialog: Dialog?) = mutableState.update { it.copy(dialog = dialog) }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<NovelHistoryWithRelations>? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: NovelHistoryWithRelations) : Dialog
        data class ChangeCategory(
            val novelId: Long,
            val categories: List<NovelCategory>,
            val currentIds: Set<Long>,
        ) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val novelId: Long, val chapterId: Long?) : Event
        data class OpenNovel(val source: String, val url: String) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
