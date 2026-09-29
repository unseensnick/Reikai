package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.RandomMangaSource
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.eHentaiSourceIds
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import reikai.domain.source.filter.selectGenre
import reikai.presentation.browse.MangaAddFlow
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.catalogue.BrowseColumns
import reikai.presentation.browse.catalogue.trackBrowseColumns
import reikai.presentation.browse.catalogue.trackDisplayMode
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

// RK: open, with createSourcePagingSource / combineMetadata as overridable hooks and a `filterable`
// state flag, so the MangaDex follows screen can subclass this and swap in its own paging source
// (mirrors Komikku's BrowseSourceScreenModel extension surface).
@AssistedInject
open class BrowseSourceViewModel(
    @Assisted private val sourceId: Long,
    @Assisted listingQuery: String?,
    sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val getRemoteManga: GetRemoteManga,
    private val getManga: GetManga,
    getIncognitoState: GetIncognitoState,
    // RK --> the last-used source is shared with the novel catalogue, so it is written to one key
    reikaiSourcePreferences: ReikaiSourcePreferences,
    // RK <--
    // RK --> favorite / category / duplicate orchestration extracted to the shared MangaLibraryAdder
    private val mangaLibraryAdder: MangaLibraryAdder,
    // RK <--
    // RK --> metadata DB-join for adult-source rich browse rows
    private val getFlatMetadataById: GetFlatMetadataById,
    // RK <--
) : ViewModel() {

    val state: StateFlow<BrowseSourceViewModel.State>
        field = MutableStateFlow<BrowseSourceViewModel.State>(State(Listing.valueOf(listingQuery)))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, listingQuery: String?): BrowseSourceViewModel
    }

    // RK --> upstream keeps this a Compose `asState` property the screen reads from composition.
    //        Reikai's catalogue draws from `state`, so the value travels there instead and the
    //        preference is written through this verb.
    private val displayModePreference = sourcePreferences.sourceDisplayMode

    fun setDisplayMode(mode: LibraryDisplayMode) = displayModePreference.set(mode)
    // RK <--

    // Null until the extension scan has finished and the source could be resolved.
    val source: Source? get() = state.value.source

    // RK: gate the rich adult-source browse rows on the EH/ExH source set + the enhanced-view pref
    private val enhancedEhView = sourcePreferences.enableEnhancedEhView.get()
    val useEhentaiView: Boolean
        get() = source?.id in eHentaiSourceIds && enhancedEhView

    init {
        viewModelScope.launchIO {
            val source = sourceManager.getOrStub(sourceId)

            state.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    source = source,
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }

            if (!getIncognitoState.await(source.id)) {
                // RK: one key for both content types (see ReikaiSourcePreferences.lastUsedSource).
                reikaiSourcePreferences.lastUsedSource.set(SourceKey.Manga(source.id))
            }
        }

        // RK: shared with the novel catalogue, which owes the same invariant.
        displayModePreference.trackDisplayMode(viewModelScope) { mode ->
            state.update { it.copy(displayMode = mode) }
        }
        // RK: the library's column counts, which the shared catalogue grid follows for both types.
        libraryPreferences.trackBrowseColumns(viewModelScope) { columns ->
            state.update { it.copy(columns = columns) }
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val mangaPagerFlowFlow = state.map { it.source to it.listing }
        .filter { (source, _) -> source != null }
        .map { (_, listing) -> listing }
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
                        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), manga to metadata)
                }
                    .filter { !hideInLibraryItems || !it.value.first.favorite }
                // RK <--
            }
                .cachedIn(viewModelScope)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyFlow())

    // RK --> DB-join each manga with its persisted metadata (falling back to the metadata carried
    //        from paging) so adult-source browse rows can render rating / tags / pages. Ported from
    //        Komikku's combineMetadata; mirrors MetadataViewScreenModel's getMainSource + raise().
    //        `open` so the follows screen can pass the metadata straight through.
    open fun Flow<Manga>.combineMetadata(
        metadata: RaisedSearchMetadata?,
    ): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        val metadataSource = source?.getMainSource<MetadataSource<*, *>>()
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

    fun resetFilters() {
        val source = source ?: return
        state.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        state.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        state.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source?.getFilterList() ?: FilterList())

        state.update {
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
        val defaultFilters = source?.getFilterList() ?: return
        // RK: the matcher moved to reikai.domain.source.filter.selectGenre, shared with novel sources
        val genreExists = defaultFilters.selectGenre(genreName)

        state.update {
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

    // RK --> upstream's changeMangaFavorite, addFavorite and duplicate lookup moved to the long-press
    //        add flow every browse surface shares, inherited by MangaDexFollowsViewModel.
    val addFlow = MangaAddFlow(mangaLibraryAdder, viewModelScope)
    // RK <--

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        state.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        state.update { it.copy(toolbarQuery = query) }
    }

    // RK -->
    // Fetch a random MangaDex title id, then expose it as a one-shot nav target. The fetch is async,
    // so the screen navigates from a LaunchedEffect on the state rather than a direct push in the
    // click (pushing from an async callback can fail to render).
    fun onMangaDexRandom() {
        viewModelScope.launchIO {
            // A random-endpoint error (rate limit, transient 5xx, dropped connection) must not crash
            // the app; the button just does nothing on failure.
            val id = runCatching { source?.getMainSource<RandomMangaSource>()?.fetchRandomMangaUrl() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?: return@launchIO
            state.update { it.copy(randomMangaTarget = "id:$id") }
        }
    }

    fun consumeRandomTarget() {
        state.update { it.copy(randomMangaTarget = null) }
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
        // RK: the long-press dialogs moved to MangaAddDialog, which addFlow raises
    }

    @Immutable
    data class State(
        val listing: Listing,
        val source: Source? = null,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        // RK: one-shot nav target for the MangaDex "Random" button (an "id:<uuid>" search).
        val randomMangaTarget: String? = null,
        // RK: the grid layout. It lives in the state because the catalogue screen renders off this
        //     flow rather than reading the model from composition, so a Compose-only value set here
        //     would change nothing on screen until some unrelated update happened to emit.
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.default,
        // RK: the grid's column counts, carried here for the same reason as [displayMode].
        val columns: BrowseColumns = BrowseColumns(),
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}
