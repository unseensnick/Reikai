package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.category.resolveDefaultCategoryIds
import reikai.domain.manga.MangaMergeManager
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddOutcome
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.addEntry
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.finishAdd
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class HistoryViewModel(
    private val addTracks: AddTracks = Injekt.get(),
    // RK: per-entry custom title/cover overrides, overlaid on the displayed rows (display-only)
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    private val sourceManager: SourceManager = Injekt.get(),
    // RK: add-time grouping (the suggestion gate, the merge, and the group's categories).
    private val mergeManager: MangaMergeManager = Injekt.get(),
    private val mangaLibraryAdder: MangaLibraryAdder = Injekt.get(),
    // RK: the History tab's category filter, one selection covering both content types.
    private val reikaiSourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : ViewModel() {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    // RK: the recents category filter is a query parameter, so it joins the search query in the key
    //     the subscription re-runs on.
    private val history: StateFlow<List<HistoryUiModel>?> = combine(
        searchQuery,
        reikaiSourcePreferences.recentsCategoryFilterFlow(RecentsSurface.HISTORY),
        ::Pair,
    )
        .distinctUntilChanged()
        .flatMapLatest { (query, categories) ->
            // RK: overlay the display-only custom title/cover onto each row, keyed by the real manga
            //     id. The SQL search (getHistory.subscribe) still runs on the raw title.
            combine(
                getHistory.subscribe(query ?: "", categories.include, categories.exclude),
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
                .map { it.toHistoryUiModels() }
                .flowOn(Dispatchers.IO)
        }
        // RK: seeded null, where upstream seeds an empty list. Null is this feed's "not loaded yet",
        //     read by the shared screen and by the recents read lane; an empty seed would make both
        //     announce an empty history a tick before the query answers.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(
        searchQuery,
        history,
        dialog,
    ) { searchQuery, history, dialog ->
        State(searchQuery = searchQuery, list = history, dialog = dialog)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private fun List<HistoryWithRelations>.toHistoryUiModels(): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    suspend fun getNextChapter(): Chapter? {
        return withIOContext { getNextChapters.await(onlyUnread = false).firstOrNull() }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        viewModelScope.launchIO {
            sendNextChapterEvent(getNextChapters.await(mangaId, chapterId, onlyUnread = false))
        }
    }

    private suspend fun sendNextChapterEvent(chapters: List<Chapter>) {
        val chapter = chapters.firstOrNull()
        _events.send(Event.OpenChapter(chapter))
    }

    // RK: the latest manga read, for the tab-reselect global-latest resume. Reads the unfiltered
    //     query rather than the rendered feed, so a category filter cannot change what resume opens.
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

    fun removeAllHistory() {
        viewModelScope.launchIO {
            val result = removeHistory.awaitAll()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        searchQuery.update { query }
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.update { dialog }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    // RK: upstream's Category? overload went with the add path that needed it; the shared
    // default-category rule already hands back the id list this takes.
    private fun moveMangaToCategory(mangaId: Long, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    // RK: the picker's confirm owes both writes the add deferred, in the shared order, so backing out
    // of the picker adds nothing and a failed favorite leaves no categories behind.
    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        viewModelScope.launchIO {
            finishAdd(
                categoryIds = categories,
                favorite = { manga.id.takeIf { manga.favorite || updateManga.awaitUpdateFavorite(manga.id, true) } },
                fileCategories = { id, categoryIds -> setMangaCategories.await(id, categoryIds) },
            )
        }
    }

    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun addFavorite(mangaId: Long) {
        viewModelScope.launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO

            val duplicates = getDuplicateLibraryManga(manga)
            if (duplicates.isNotEmpty()) {
                val groupIdByMangaId = mergeManager.groupIdsFor(duplicates.map { it.manga.id })
                dialog.update {
                    Dialog.DuplicateManga(
                        manga,
                        duplicates,
                        mergeManager.suggestGroupingOnAdd,
                        groupIdByMangaId,
                        mangaLibraryAdder.duplicateSourceLabels(duplicates),
                    )
                }
                return@launchIO
            }

            addFavorite(manga)
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launchIO {
            // RK: the shared add sequence every other add path runs: decide, favorite, file, and
            // abandon the whole add if the favorite write fails.
            val outcome = addEntry(
                resolveCategories = {
                    resolveDefaultCategoryIds(getCategories(), libraryPreferences.defaultCategory.get())
                },
                favorite = { manga.id.takeIf { updateManga.awaitUpdateFavorite(manga.id, true) } },
                fileCategories = { id, categoryIds -> setMangaCategories.await(id, categoryIds) },
            )
            when (outcome) {
                AddOutcome.Failed -> return@launchIO
                AddOutcome.NeedsCategoryChoice -> showChangeCategoryDialog(manga)
                AddOutcome.Added -> {}
            }

            // Sync with tracking services if applicable
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
        }
    }

    // RK: add-time grouping. Only the picks the user chose: the duplicate list is fuzzy, so merging
    // every match would fuse distinct series. The favorite-and-merge pair and the reason it has to be
    // atomic live in MangaLibraryAdder.addToGroup; null means it wrote nothing.
    fun addToExistingGroup(manga: Manga, selectedIds: List<Long>) {
        viewModelScope.launchIO {
            val seeded = mangaLibraryAdder.addToGroup(manga, selectedIds) ?: return@launchIO
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))

            // The group's categories win: only fall back to the default (or the picker) when the group
            // is uncategorized, so the new source lands where the rest of the series lives.
            if (!seeded) {
                val directIds = resolveDefaultCategoryIds(getCategories(), libraryPreferences.defaultCategory.get())
                if (directIds != null) moveMangaToCategory(manga.id, directIds) else showChangeCategoryDialog(manga)
            }
        }
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        dialog.update { Dialog.Migrate(target = target, current = current) }
    }

    fun showChangeCategoryDialog(manga: Manga) {
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            dialog.update {
                Dialog.ChangeCategory(
                    manga = manga,
                    initialSelection = categories.mapAsCheckboxState { it.id in selection },
                )
            }
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<HistoryUiModel>? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val suggestGroup: Boolean,
            val groupIdByMangaId: Map<Long, Long>,
            val sourceLabels: Map<Long, EntrySourceLabel>,
        ) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
