package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.RandomMangaSource
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.eHentaiSourceIds
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.MangaLibraryAdder
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

// RK: open, with createSourcePagingSource / combineMetadata as overridable hooks and a `filterable`
// state flag, so the MangaDex follows screen can subclass this and swap in its own paging source
// (mirrors Komikku's BrowseSourceScreenModel extension surface).
open class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    getIncognitoState: GetIncognitoState = Injekt.get(),
    // RK --> favorite / category / duplicate orchestration extracted to the shared MangaLibraryAdder
    private val mangaLibraryAdder: MangaLibraryAdder = Injekt.get(),
    // RK <--
    // RK --> metadata DB-join for adult-source rich browse rows
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    // RK <--
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    // RK: gate the rich adult-source browse rows on the EH/ExH source set + the enhanced-view pref
    val useEhentaiView = source.id in eHentaiSourceIds && sourcePreferences.enableEnhancedEhView.get()

    init {
        mutableState.update {
            var query: String? = null
            var listing = it.listing

            if (listing is Listing.Search) {
                query = listing.query
                listing = Listing.Search(query, source.getFilterList())
            }

            it.copy(
                listing = listing,
                filters = source.getFilterList(),
                toolbarQuery = query,
            )
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val mangaPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                // RK: overridable so subclasses (MangaDex follows) can supply their own paging source
                createSourcePagingSource(listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                // RK --> carry each manga's metadata alongside it for the rich browse rows
                pagingData.map { (manga, metadata) ->
                    getManga.subscribe(manga.url, manga.source)
                        .map { it ?: manga }
                        .combineMetadata(metadata)
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.first.favorite }
                // RK <--
            }
                .cachedIn(ioCoroutineScope)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    // RK --> DB-join each manga with its persisted metadata (falling back to the metadata carried
    //        from paging) so adult-source browse rows can render rating / tags / pages. Ported from
    //        Komikku's combineMetadata; mirrors MetadataViewScreenModel's getMainSource + raise().
    //        `open` so the follows screen can pass the metadata straight through.
    open fun Flow<Manga>.combineMetadata(
        metadata: RaisedSearchMetadata?,
    ): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        val metadataSource = source.getMainSource<MetadataSource<*, *>>()
        return flatMapLatest { manga ->
            if (metadataSource != null) {
                getFlatMetadataById.subscribe(manga.id).map { flat ->
                    manga to (flat?.raise(metadataSource.metaClass) ?: metadata)
                }
            } else {
                flowOf(manga to null)
            }
        }
    }

    // RK: overridable paging-source factory. The default browses the source; the follows screen
    //     overrides it to page the signed-in user's MangaDex follow list.
    open fun createSourcePagingSource(query: String, filters: FilterList): SourcePagingSource {
        return getRemoteManga(sourceId, query, filters)
    }
    // RK <--

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun searchGenre(genreName: String) {
        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    // RK --> favorite / category / duplicate flow delegated to the shared MangaLibraryAdder
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch { mangaLibraryAdder.changeFavorite(manga) }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            when (val result = mangaLibraryAdder.resolveAddFavorite(manga)) {
                AddFavoriteResult.Added -> {}
                is AddFavoriteResult.NeedsCategoryChoice ->
                    setDialog(Dialog.ChangeMangaCategory(manga, result.initialSelection))
            }
        }
    }

    // RK: add-time grouping (see MangaLibraryAdder).
    val suggestGrouping: Boolean get() = mangaLibraryAdder.suggestGrouping

    suspend fun getDuplicateGroupIds(duplicates: List<MangaWithChapterCount>): Map<Long, Long> =
        mangaLibraryAdder.getDuplicateGroupIds(duplicates)

    fun addToExistingGroup(manga: Manga, selectedIds: List<Long>) {
        screenModelScope.launch {
            when (val result = mangaLibraryAdder.addToExistingGroup(manga, selectedIds)) {
                AddFavoriteResult.Added -> {}
                is AddFavoriteResult.NeedsCategoryChoice ->
                    setDialog(Dialog.ChangeMangaCategory(manga, result.initialSelection))
            }
        }
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return mangaLibraryAdder.getDuplicates(manga)
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO { mangaLibraryAdder.moveToCategories(manga, categoryIds) }
    }
    // RK <--

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    // RK -->
    // Fetch a random MangaDex title id, then expose it as a one-shot nav target. The fetch is async,
    // so the screen navigates from a LaunchedEffect on the state rather than a direct push in the
    // click (pushing from an async callback can fail to render). (Phase 6)
    fun onMangaDexRandom() {
        screenModelScope.launchIO {
            // A random-endpoint error (rate limit, transient 5xx, dropped connection) must not crash
            // the app; the button just does nothing on failure.
            val id = runCatching { source.getMainSource<RandomMangaSource>()?.fetchRandomMangaUrl() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?: return@launchIO
            mutableState.update { it.copy(randomMangaTarget = "id:$id") }
        }
    }

    fun consumeRandomTarget() {
        mutableState.update { it.copy(randomMangaTarget = null) }
    }
    // RK <--

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val suggestGroup: Boolean,
            val groupIdByMangaId: Map<Long, Long>,
        ) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        // RK: one-shot nav target for the MangaDex "Random" button (an "id:<uuid>" search). (Phase 6)
        val randomMangaTarget: String? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}
