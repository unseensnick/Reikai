package reikai.presentation.novel.globalsearch

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.novel.NovelRepository
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.injectLazy

/** Max sources searched concurrently, matching the manga global search's throttle. */
private const val SEARCH_CONCURRENCY = 5

/**
 * Cross-source light-novel search. Fans [NovelSource.searchNovels] out across every installed source
 * under a [Semaphore], updating each source's row independently as it completes so results fill in
 * progressively (mirrors Mihon's `SearchScreenModel`).
 */
class NovelGlobalSearchScreenModel(
    initialQuery: String,
) : StateScreenModel<NovelGlobalSearchState>(NovelGlobalSearchState(query = initialQuery)) {

    private val manager: NovelSourceManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()

    private var searchJob: Job? = null

    init {
        screenModelScope.launchIO {
            try { installer.ensureLoaded() } catch (_: Throwable) {}
            if (initialQuery.isNotBlank()) search(initialQuery)
        }
        // In-library marking, same read-only (source, url) key set as browse.
        screenModelScope.launchIO {
            novelRepository.getFavoritedKeysAsFlow().collectLatest { keys ->
                mutableState.update { it.copy(favoritedKeys = keys) }
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val sources = manager.getAll()
        mutableState.update {
            it.copy(
                query = query,
                results = sources.map { source -> SourceSearchResult(source, SearchState.Loading) },
            )
        }
        if (query.isBlank()) return
        searchJob = screenModelScope.launchIO {
            val semaphore = Semaphore(SEARCH_CONCURRENCY)
            sources.map { source ->
                async {
                    val result = semaphore.withPermit {
                        try {
                            SearchState.Success(source.searchNovels(query, 1))
                        } catch (e: Throwable) {
                            SearchState.Error("${e.javaClass.simpleName}: ${e.message ?: ""}")
                        }
                    }
                    mutableState.update { st ->
                        st.copy(
                            results = st.results.map {
                                if (it.source.id == source.id) it.copy(state = result) else it
                            },
                        )
                    }
                }
            }.awaitAll()
        }
    }
}

data class NovelGlobalSearchState(
    val query: String = "",
    val results: List<SourceSearchResult> = emptyList(),
    /** (source, url) pairs in the library, for in-library marking of results. */
    val favoritedKeys: Set<Pair<String, String>> = emptySet(),
)

/** One source's slice of a global search: the source plus its independent load state. */
data class SourceSearchResult(
    val source: NovelSource,
    val state: SearchState,
)

sealed interface SearchState {
    data object Loading : SearchState

    /** Completed; [novels] empty means the source returned no matches. */
    data class Success(val novels: List<NovelItem>) : SearchState

    data class Error(val message: String) : SearchState
}
