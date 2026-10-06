package reikai.presentation.novel.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import reikai.domain.novel.model.NovelChapter
import reikai.novel.content.ChapterSearchSnippet
import reikai.novel.content.ChapterTextSearch
import reikai.novel.content.NovelRegexReplacements
import reikai.novel.download.NovelDownloadedTexts
import tachiyomi.core.common.util.lang.launchIO
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.PatternSyntaxException

/**
 * Searches the text of every downloaded chapter of a novel, in the scope its reader opens: the source
 * [sourceScoped] names, or [novelId]'s whole merge group. Novels only, since a manga chapter on disk is
 * images with no text to search. Ported from Tsundoku's entry chapter search.
 */
class NovelChapterSearchScreen(
    private val novelId: Long,
    private val sourceScoped: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(novelId = novelId, sourceScoped = sourceScoped)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        NovelChapterSearchContent(
            state = state,
            navigateUp = navigator::pop,
            onQueryChange = viewModel::updateQuery,
            onSearch = viewModel::search,
            onOptionsChange = viewModel::updateOptions,
            onResultClick = { chapter ->
                context.startActivity(
                    ReaderActivity.newNovelIntent(context, chapter.novelId, chapter.id, sourceScoped),
                )
            },
        )
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val novelId: Long,
        @Assisted private val sourceScoped: Boolean,
        private val downloadedTexts: NovelDownloadedTexts,
    ) : ViewModel() {

        val state: StateFlow<State>
            field = MutableStateFlow(State())

        private var searchJob: Job? = null
        private val searchGeneration = AtomicLong()

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(novelId: Long, sourceScoped: Boolean): Model
        }

        init {
            viewModelScope.launchIO {
                val chapters = downloadedTexts.chaptersOf(novelId, sourceScoped).onDisk
                state.update { it.copy(chapters = chapters) }
                if (state.value.pendingSearch) search()
            }
        }

        fun updateQuery(query: String) {
            state.update { it.copy(query = query, regexError = null) }
        }

        fun updateOptions(options: SearchOptions) {
            state.update { it.copy(options = options, regexError = null) }
            if (state.value.submittedQuery != null) search()
        }

        fun search() {
            searchJob?.cancel()
            // A cancelled search can still be inside an update when the next one starts, so each update
            // checks it belongs to the latest search.
            val generation = searchGeneration.incrementAndGet()
            val current = state.value
            if (current.query.isEmpty()) {
                state.update { it.copy(isSearching = false, pendingSearch = false) }
                return
            }
            val regex = try {
                current.options.let {
                    NovelRegexReplacements.findRegex(current.query, it.isRegex, it.wholeWord, it.caseSensitive)
                }
            } catch (e: PatternSyntaxException) {
                state.update { it.copy(regexError = e.description, isSearching = false) }
                return
            }
            val chapters = current.chapters
            if (chapters == null) {
                state.update { it.copy(pendingSearch = true) }
                return
            }
            state.update {
                it.copy(
                    submittedQuery = current.query,
                    pendingSearch = false,
                    isSearching = true,
                    searchedCount = 0,
                    failedCount = 0,
                    results = emptyList(),
                )
            }
            searchJob = viewModelScope.launchIO {
                val context = currentCoroutineContext()
                downloadedTexts.readEach(chapters) { chapter, text ->
                    val matches = text?.let {
                        ChapterTextSearch.findMatches(it, regex, isActive = { context.isActive })
                    }
                    updateFor(generation) { s ->
                        s.copy(
                            searchedCount = s.searchedCount + 1,
                            failedCount = s.failedCount + if (text == null) 1 else 0,
                            results = if (matches != null && matches.count > 0) {
                                s.results + SearchResult(chapter, matches.count, matches.snippets)
                            } else {
                                s.results
                            },
                        )
                    }
                }
                updateFor(generation) { it.copy(isSearching = false) }
            }
        }

        private inline fun updateFor(generation: Long, crossinline transform: (State) -> State) {
            state.update { if (searchGeneration.get() == generation) transform(it) else it }
        }
    }

    @Immutable
    data class SearchOptions(
        val isRegex: Boolean = false,
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
    )

    @Immutable
    data class SearchResult(
        val chapter: NovelChapter,
        val matchCount: Int,
        val snippets: List<ChapterSearchSnippet>,
    )

    @Immutable
    data class State(
        /** The downloaded chapters in reading order; null while they load. */
        val chapters: List<NovelChapter>? = null,
        val query: String = "",
        val options: SearchOptions = SearchOptions(),
        val submittedQuery: String? = null,
        /** A search asked for before [chapters] loaded, run once they have. */
        val pendingSearch: Boolean = false,
        val regexError: String? = null,
        val isSearching: Boolean = false,
        val searchedCount: Int = 0,
        val failedCount: Int = 0,
        val results: List<SearchResult> = emptyList(),
    ) {
        val totalMatches: Int get() = results.sumOf { it.matchCount }
    }
}
