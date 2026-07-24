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
import kotlinx.coroutines.flow.combine
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
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.GetNovelHistory
import reikai.domain.novel.interactor.RemoveNovelHistory
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
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
    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo = Injekt.get(),
    private val removeNovelHistory: RemoveNovelHistory = Injekt.get(),
    private val getNextNovelChapter: GetNextNovelChapter = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val novelLibraryAdder: NovelLibraryAdder = Injekt.get(),
    // RK: add-time grouping (the merge itself; the gate and the group's categories go through the adder).
    private val novelMergeManager: NovelMergeManager = Injekt.get(),
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
                    // Overlay the display-only custom title/cover onto each row, keyed by the real
                    // novel id. The SQL search (getNovelHistory.subscribe) still runs on the raw title.
                    combine(
                        getNovelHistory.subscribe(query ?: ""),
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

    /** Add a not-yet-library novel from its history row. Warn on a similarly-named library novel first
     *  (mirrors HistoryScreenModel), then favorite the existing row and apply the default category or
     *  prompt (reuses NovelLibraryAdder's add-to-library category logic). */
    fun addFavorite(novelId: Long) {
        screenModelScope.launchIO {
            val novel = novelRepository.getById(novelId) ?: return@launchIO
            novelLibraryAdder.findDuplicates(novel.id, novel.title)?.let { dup ->
                setDialog(
                    Dialog.DuplicateNovel(
                        novelId,
                        dup.duplicates,
                        dup.sourceNames,
                        dup.sourceSites,
                        novelLibraryAdder.suggestGrouping,
                        novelLibraryAdder.getDuplicateGroupIds(dup.duplicates),
                    ),
                )
                return@launchIO
            }
            addToLibrary(novelId)
        }
    }

    /** Proceed with the add after the possible-duplicate dialog's "Add anyway". */
    fun addFavoriteAnyway(novelId: Long) {
        screenModelScope.launchIO { addToLibrary(novelId) }
    }

    /** The target for the migrate-from-duplicate flow: a history novel is already a chapter-synced row. */
    suspend fun novelForMigrate(novelId: Long): Novel? = novelRepository.getById(novelId)

    /** Add-time grouping. Merge the novel with the duplicates the user picked, then add it (only the
     *  picks: the duplicate list is fuzzy, so merging every match would fuse distinct series). Seeding
     *  first is what makes the category step open on the group's own categories. */
    fun addToExistingGroup(novelId: Long, selectedIds: List<Long>) {
        screenModelScope.launchIO {
            novelMergeManager.merge(listOf(novelId) + selectedIds)
            val seeded = novelLibraryAdder.seedCategoriesFromGroup(novelId, selectedIds)
            updateNovel.awaitUpdateFavorite(novelId, favorite = true)
            // Group categories win: only fall back to the default (or picker) for an uncategorized group.
            if (!seeded) {
                novelLibraryAdder.applyDefaultCategoryOrPrompt(novelId)?.let { prompt ->
                    setDialog(Dialog.ChangeCategory(novelId, prompt.categories, prompt.currentIds))
                }
            }
        }
    }

    private suspend fun addToLibrary(novelId: Long) {
        updateNovel.awaitUpdateFavorite(novelId, favorite = true)
        novelLibraryAdder.applyDefaultCategoryOrPrompt(novelId)?.let { prompt ->
            setDialog(Dialog.ChangeCategory(novelId, prompt.categories, prompt.currentIds))
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
        data class DuplicateNovel(
            val novelId: Long,
            val duplicates: List<NovelWithChapterCount>,
            val sourceNames: Map<String, String>,
            val sourceSites: Map<String, String?>,
            /** Whether to offer add-time grouping (the same-title suggestion pref plus the master switch). */
            val suggestGroup: Boolean,
            /** Novel id -> group id, so same-group duplicates collapse into one card. */
            val groupIdByNovelId: Map<Long, Long>,
        ) : Dialog
        data class ChangeCategory(
            val novelId: Long,
            val categories: List<Category>,
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
