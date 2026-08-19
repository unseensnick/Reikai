package reikai.presentation.migrate.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
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
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import mihon.app.di.appGraph
import reikai.domain.library.ContentType
import reikai.presentation.browse.EntrySearchSourceFilterChips
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** Sources searched at once. */
private const val SEARCH_CONCURRENCY = 5

/**
 * Migrating a single entry: search the chosen sources and pick the target directly.
 *
 * A one-entry migration has nothing to accept in bulk and no progress worth a list, so it skips
 * straight to the results, and tapping one opens the shared migrate dialog. The batch list serves
 * everything else.
 */
class EntryMigrationSearchScreen(
    private val contentType: ContentType,
    private val entryId: Long,
    /** The extra search term for this run; see [MigrationTuning.extraQuery]. */
    private val extraQuery: String?,
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<EntryMigrationSearchViewModel, EntryMigrationSearchViewModel.Factory> {
            create(
                entryId = entryId,
                adapter = context.appGraph.migrationAdapters.forType(contentType),
                extraQuery = extraQuery,
                io = Dispatchers.IO,
            )
        }
        val state by viewModel.state.collectAsState()
        var query by rememberSaveable(state.entry?.title) { mutableStateOf(state.entry?.title.orEmpty()) }

        if (state.isLoading) {
            LoadingScreen()
            return
        }
        val entry = state.entry
        if (entry == null) {
            // The entry vanished between opening this screen and loading it (removed from the
            // library elsewhere); say so rather than showing an empty search.
            EmptyScreen(stringRes = MR.strings.internal_error)
            return
        }

        // A deep pick is made on a screen pushed over this one, so it is collected on the way back.
        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) viewModel.collectPendingPick()
        }

        PickOutcomeToast(state.pickOutcome, viewModel::consumePickOutcome)

        Scaffold(
            topBar = { scrollBehavior ->
                // The global-search header shape: search field in the toolbar, progress under it,
                // then the has-results chip. Same surface, same reading.
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Box {
                        SearchToolbar(
                            searchQuery = query,
                            onChangeSearchQuery = { query = it.orEmpty() },
                            onSearch = { viewModel.search(it) },
                            onClickCloseSearch = navigator::pop,
                            navigateUp = navigator::pop,
                            scrollBehavior = scrollBehavior,
                        )
                        val progress = state.searchedCount
                        val total = state.sections.size
                        if (progress in 1..<total) {
                            LinearProgressIndicator(
                                progress = { progress / total.toFloat() },
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                    EntrySearchSourceFilterChips(
                        isPinnedOnly = false,
                        onlyShowHasResults = state.onlyShowHasResults,
                        // The sources searched are the configured migration targets; the
                        // pinned/all source filter has no meaning here.
                        showSourceFilter = false,
                        onSelectPinnedOnly = {},
                        onSelectAll = {},
                        onToggleResults = viewModel::toggleOnlyResults,
                    )
                }
            },
        ) { contentPadding ->
            // A source that failed is kept even under the filter, matching the batch list: the user
            // needs to tell "could not answer" from "answered nothing", and hiding it takes the
            // retry with it.
            val sections = if (state.onlyShowHasResults) {
                state.sections.filter { it.result.hasSomethingToSay }
            } else {
                state.sections
            }
            if (sections.isEmpty()) {
                // Never a bare blank body: with every source filtered away there is nothing on
                // screen to explain where the results went.
                EmptyScreen(
                    stringRes = if (state.sections.isEmpty()) {
                        MR.strings.migrationFlow_emptyNoSources
                    } else {
                        MR.strings.migrationFlow_emptySourcesFiltered
                    },
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(items = sections, key = { it.sourceKey }) { section ->
                    MigrationCandidateStrip(
                        sourceName = section.sourceName,
                        sourceLang = section.sourceLang,
                        isCurrentSource = section.sourceKey == entry.sourceKey,
                        result = section.result,
                        onPick = viewModel::showDialog,
                        onPreview = { it.openDetails(navigator) },
                        onBrowseSource = {
                            if (!openDeepPicker(navigator, entry, section.sourceKey, query)) {
                                context.toast(MR.strings.internal_error)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaterialTheme.padding.small),
                    )
                }
            }
        }

        val target = state.dialogTarget
        if (target != null) {
            EntryMigrateDialog(
                contentType = contentType,
                entry = entry,
                target = target,
                onDismissRequest = viewModel::dismissDialog,
                // Show opens the candidate, not the entry: the target is the thing being decided on,
                // and the entry is the one the user already knows.
                onShowEntry = {
                    viewModel.dismissDialog()
                    target.openDetails(navigator)
                },
                onFinished = { replaced, resolved ->
                    viewModel.dismissDialog()
                    // Leave the whole flow behind before landing, so back does not return to
                    // results (or a stale config step) for a migration that already happened.
                    navigator.popUntil { it !is MigrationFlowScreen }
                    // The resolved candidate, not the raw pick: replace-detection needs the
                    // stored row behind it to swap out the migrated-away details page.
                    resolved.openDetailsAfterCommit(navigator, replaced, migrated = entry)
                },
            )
        }
    }
}

@AssistedInject
class EntryMigrationSearchViewModel(
    @Assisted private val entryId: Long,
    // Passed in rather than resolved here, the same reason the list model takes its own: the search
    // this route runs is otherwise reachable only by reading the code, which is how the extra query
    // came to be dropped on it without a test noticing. The screen passes them in.
    @Assisted private val adapter: MigrationFlowAdapter,
    /** The extra search term for this run; see [MigrationTuning.extraQuery]. */
    @Assisted private val extraQuery: String? = null,
    @Assisted private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pickHandoff: MigrationPickHandoff,
) : ViewModel() {

    val state: StateFlow<EntryMigrationSearchViewModel.State>
        field = MutableStateFlow<EntryMigrationSearchViewModel.State>(State())

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            entryId: Long,
            adapter: MigrationFlowAdapter,
            extraQuery: String?,
            io: CoroutineDispatcher,
        ): EntryMigrationSearchViewModel
    }

    private val permits = Semaphore(SEARCH_CONCURRENCY)

    @Volatile
    private var searchJob: Job? = null

    init {
        viewModelScope.launch(io) {
            adapter.prepare()
            val entry = adapter.loadEntries(listOf(entryId)).firstOrNull()
            state.update { it.copy(isLoading = false, entry = entry) }
            if (entry != null) search(entry.title)
        }
    }

    /**
     * Search every chosen source for [query]. A new search supersedes the one before it, and the
     * per-source writes check they still belong to the current search, since cancelling cannot stop
     * a coroutine that is already past its last suspension point.
     */
    fun search(query: String) {
        val entry = state.value.entry ?: return
        if (query.isBlank()) return
        val sources = adapter.sourcesFor()
        val fullQuery = query.withExtraQuery(extraQuery)
        searchJob?.cancel()
        state.update { state ->
            state.copy(sections = sources.map { Section(it.key, it.name, it.lang) })
        }
        searchJob = viewModelScope.launch(io) {
            val myJob = coroutineContext[Job]
            adapter.fanOutCandidates(
                entry = entry,
                query = fullQuery,
                sources = sources,
                permits = permits,
                isCurrent = { searchJob === myJob },
            ) { sourceKey, landed ->
                state.update { state ->
                    state.copy(
                        sections = state.sections.map {
                            if (it.sourceKey == sourceKey) it.copy(result = landed) else it
                        },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // An uncollected pick belongs to this migration only.
        pickHandoff.clear()
    }

    /** Held in model state, not the composition, so a rotation mid-migrate keeps the dialog alive
     *  to receive its result. */
    fun showDialog(target: MigrationCandidate) = state.update { it.copy(dialogTarget = target) }

    fun dismissDialog() = state.update { it.copy(dialogTarget = null) }

    fun toggleOnlyResults() = state.update { it.copy(onlyShowHasResults = !it.onlyShowHasResults) }

    /**
     * Open the migrate dialog for a target picked on a pushed browse screen, if one came back for
     * this entry. Called when the screen returns to the foreground; the pick is a stored row
     * already, so it wraps into a resolved candidate with no search.
     */
    fun collectPendingPick() {
        val entry = state.value.entry ?: return
        viewModelScope.launch(io) {
            when (val pick = adapter.takePendingPick(pickHandoff, entry.id)) {
                null -> return@launch
                is PendingPick.Ready -> state.update { it.copy(dialogTarget = pick.candidate) }
                is PendingPick.Rejected -> state.update { it.copy(pickOutcome = pick.outcome) }
            }
        }
    }

    /** Called once the screen has shown the outcome; see [PickOutcome]. */
    fun consumePickOutcome() = state.update { it.copy(pickOutcome = null) }

    data class Section(
        val sourceKey: String,
        val sourceName: String,
        /** Raw language tag, localized at render (shared header shows it like global search). */
        val sourceLang: String = "",
        val result: StripResult = StripResult.Loading,
    )

    data class State(
        val isLoading: Boolean = true,
        val entry: MigrationEntry? = null,
        val sections: List<Section> = emptyList(),
        val dialogTarget: MigrationCandidate? = null,
        /** Consume-once: see [PickOutcome]. */
        val pickOutcome: PickOutcome? = null,
        val onlyShowHasResults: Boolean = false,
    ) {
        val searchedCount: Int get() = sections.count { it.result !is StripResult.Loading }
    }
}
