package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import reikai.presentation.browse.MangaAddFlow
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.liveMangaRow
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.Executors

abstract class SearchViewModel(
    // RK: upstream's initialState dropped with the state it seeded (see addFlow).
    sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    // RK: upstream's `preferences` dropped, unread since the shared engine took over the search.
    // RK: the long-press add flow, shared with every browse surface
    mangaLibraryAdder: MangaLibraryAdder,
) : ViewModel() {

    // RK: a pool of its own, so blocking source calls never crowd out the shared IO dispatcher.
    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledSources.get()
    private val pinnedSources = sourcePreferences.pinnedSources.get() // RK: private, no subclass reads it any more

    protected var extensionFilter: String? = null

    // RK -->
    // Stripped to a provider for the shared global search, which owns the query, the order, how many
    // sources run at once and when a search is worth re-running. What is left is the manga sources,
    // the one-source call, and the long-press half below, which is per content type. Upstream's
    // composable getManga went with it: each result row follows its stored manga itself (liveMangaRow).
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
            return extensionManager.loadedExtensionsFlow.first()
                .filter { it.pkgName == filter }
                .flatMap { it.sources }
                .filter { it in enabled }
        }
        return enabled.filter { !pinnedOnly || isPinned(it) }
    }

    /** One source's results, made local so each row can follow its stored manga. */
    suspend fun searchSource(source: Source, query: String): List<EntryBrowseRow> {
        val page = withContext(coroutineDispatcher) {
            source.getSearchManga(1, query, source.getFilterList())
        }
        return page.mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
            .let { networkToLocalManga(it) }
            .map { liveMangaRow(it, getManga.subscribe(it.url, it.source)) }
    }
    // RK <--

    // RK: upstream's state, its dialog and setMigrateDialog / clearDialog are gone: the results moved to
    //     the shared global-search engine and every long-press dialog, the migrate one included, to the
    //     shared add flow below, which the screen renders for both content types.
    val addFlow = MangaAddFlow(mangaLibraryAdder, viewModelScope)
}
