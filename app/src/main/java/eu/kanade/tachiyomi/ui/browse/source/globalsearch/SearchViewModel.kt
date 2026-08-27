package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.decideAdd
import reikai.presentation.browse.finishAdd
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.Executors

abstract class SearchViewModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    private val preferences: SourcePreferences,
    // RK: shared long-press add-to-library orchestration (also used by the Browse screen)
    private val mangaLibraryAdder: MangaLibraryAdder,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow<State>(initialState)

    // A pool of its own, so blocking source calls never crowd out the shared IO dispatcher.
    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledSources.get()
    private val pinnedSources = sourcePreferences.pinnedSources.get()

    protected var extensionFilter: String? = null

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    // RK -->
    // Stripped to a provider for the shared global search, which owns the query, the order, how many
    // sources run at once and when a search is worth re-running. What is left is the manga sources,
    // the one-source call, and the long-press half below, which is per content type.
    fun isPinned(source: Source): Boolean = "${source.id}" in pinnedSources

    /**
     * The manga sources a search covers. An extension filter names one installed extension, which is
     * the deep-link case, and it overrides [pinnedOnly] because the named sources are the whole point.
     */
    suspend fun searchableSources(pinnedOnly: Boolean): List<Source> {
        val enabled = sourceManager.getAll()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }

        val filter = extensionFilter
        if (!filter.isNullOrEmpty()) {
            return extensionManager.installedExtensionsFlow.first()
                .filter { it.pkgName == filter }
                .flatMap { it.sources }
                .filter { it in enabled }
        }
        return enabled.filter { !pinnedOnly || isPinned(it) }
    }

    /** One source's results, already local so the in-library badge can resolve against them. */
    suspend fun searchSource(source: Source, query: String): List<Manga> {
        val page = withContext(coroutineDispatcher) {
            source.getSearchManga(1, query, source.getFilterList())
        }
        return page.mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
            .let { networkToLocalManga(it) }
    }
    // RK <--

    fun setMigrateDialog(currentId: Long, target: Manga) {
        viewModelScope.launchIO {
            val current = getManga.await(currentId) ?: return@launchIO
            state.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    // RK --> long-press add-to-library, via the shared MangaLibraryAdder. Results are already local
    // (networkToLocalManga), so no materialize is needed. The source is resolved per-manga inside the
    // adder since global search spans sources.
    fun setDialog(dialog: Dialog?) {
        state.update { it.copy(dialog = dialog) }
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> =
        mangaLibraryAdder.getDuplicates(manga)

    fun changeMangaFavorite(manga: Manga) {
        viewModelScope.launchIO { mangaLibraryAdder.changeFavorite(manga) }
    }

    /** RK: the shared long-press rule ([decideAdd]); twin of `BrowseSourceViewModel.onLongClick`. */
    fun onLongClick(manga: Manga) {
        viewModelScope.launchIO {
            when (
                val decision = decideAdd(
                    inLibrary = manga.favorite,
                    findDuplicates = { getDuplicateLibraryManga(manga).takeIf { it.isNotEmpty() } },
                )
            ) {
                AddDecision.Remove -> state.update { it.copy(dialog = Dialog.RemoveManga(manga)) }
                is AddDecision.ConfirmDuplicate -> state.update {
                    it.copy(
                        dialog = Dialog.AddDuplicateManga(
                            manga,
                            decision.duplicates,
                            suggestGrouping,
                            getDuplicateGroupIds(decision.duplicates),
                            mangaLibraryAdder.duplicateSourceLabels(decision.duplicates),
                        ),
                    )
                }
                AddDecision.Add -> addFavorite(manga)
            }
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launchIO {
            when (val result = mangaLibraryAdder.resolveAddFavorite(manga)) {
                // Failed wrote nothing, so there is nothing to undo and nothing to show.
                AddFavoriteResult.Added, AddFavoriteResult.Failed -> {}
                is AddFavoriteResult.NeedsCategoryChoice ->
                    state.update {
                        it.copy(dialog = Dialog.ChangeMangaCategory(manga, result.initialSelection))
                    }
            }
        }
    }

    // RK: add-time grouping (see MangaLibraryAdder).
    val suggestGrouping: Boolean get() = mangaLibraryAdder.suggestGrouping

    suspend fun getDuplicateGroupIds(duplicates: List<MangaWithChapterCount>): Map<Long, Long> =
        mangaLibraryAdder.getDuplicateGroupIds(duplicates)

    fun addToExistingGroup(manga: Manga, selectedIds: List<Long>) {
        viewModelScope.launchIO {
            when (val result = mangaLibraryAdder.addToExistingGroup(manga, selectedIds)) {
                AddFavoriteResult.Added, AddFavoriteResult.Failed -> {}
                is AddFavoriteResult.NeedsCategoryChoice ->
                    state.update {
                        it.copy(
                            dialog = Dialog.ChangeMangaCategory(
                                manga,
                                result.initialSelection,
                                alreadyFavorited = true,
                            ),
                        )
                    }
            }
        }
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        viewModelScope.launchIO { mangaLibraryAdder.moveToCategories(manga, categoryIds) }
    }

    /** RK: apply the category picker's choice, favoriting first unless the caller already did. Twin of
     *  [eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.confirmCategories]; the
     *  reason the guard lives in the model is recorded there. */
    fun confirmCategories(manga: Manga, categoryIds: List<Long>, alreadyFavorited: Boolean) {
        viewModelScope.launchIO {
            finishAdd(
                categoryIds = categoryIds,
                favorite = { manga.id.takeIf { alreadyFavorited || mangaLibraryAdder.changeFavorite(manga) } },
                fileCategories = { _, ids -> mangaLibraryAdder.moveToCategories(manga, ids) },
            )
        }
    }
    // RK <--

    fun clearDialog() {
        state.update { it.copy(dialog = null) }
    }

    // RK: the results and everything describing them moved to the shared global-search engine; what
    //     is left is this content type's own long-press dialog.
    @Immutable
    data class State(
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog

        // RK --> long-press add-to-library dialogs (rendered by the global search screen)
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val suggestGroup: Boolean,
            val groupIdByMangaId: Map<Long, Long>,
            val sourceLabels: Map<Long, EntrySourceLabel>,
        ) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
            // RK: true when the add-to-group path already favorited up front, so the confirm files
            // categories without re-toggling the favorite.
            val alreadyFavorited: Boolean = false,
        ) : Dialog
        // RK <--
    }
}
