package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import reikai.domain.library.ContentType
import reikai.presentation.browse.SOURCE_SEARCH_CONCURRENCY
import reikai.presentation.browse.globalsearch.SearchSourceFilter
import reikai.presentation.details.rememberDetailsOpener
import tachiyomi.core.common.preference.toggle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.random.Random

/**
 * Migrating a single entry: search the chosen sources and pick the target directly.
 *
 * A one-entry migration has nothing to accept in bulk and no progress worth a list, so it skips
 * straight to the results, and tapping one opens the shared migrate dialog. The batch list serves
 * everything else.
 */
@Serializable
data class EntryMigrationSearchRoute(
    val contentType: ContentType,
    val entryId: Long,
    /** The extra search term for this run; see [MigrationTuning.extraQuery]. */
    val extraQuery: String?,
    /** Keys the picks a pushed catalogue sends back; see [MigrationPickFor]. Saved with the route. */
    internal val flowId: Long = Random.nextLong(),
) : NavKey, MigrationFlowRoute

@Composable
fun EntryMigrationSearchScreen(contentType: ContentType, entryId: Long, extraQuery: String?, flowId: Long) {
    val navigator = LocalNavigator.current
    val opener = rememberDetailsOpener()
    val context = LocalContext.current
    val adapter = rememberMigrationAdapter(contentType)
    val viewModel = assistedMetroViewModel<EntryMigrationSearchViewModel, EntryMigrationSearchViewModel.Factory> {
        create(
            entryId = entryId,
            adapter = adapter,
            extraQuery = extraQuery,
            io = Dispatchers.IO,
        )
    }
    val state by viewModel.state.collectAsState()
    var query by rememberSaveable(state.entry?.title) { mutableStateOf(state.entry?.title.orEmpty()) }

    // A deep pick is made on a screen pushed over this one, so it arrives once the entry is back.
    MigrationPickEffect(flowId, viewModel, ready = !state.isLoading && state.entry != null, viewModel::takePick)

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

    PickOutcomeToast(state.pickOutcome, viewModel::consumePickOutcome)

    Scaffold(
        // No scroll behaviour passed on, as Reikai's global search does, so the bar stays one
        // plain header block with the chips under it.
        topBar = { _ ->
            Column {
                GlobalSearchToolbar(
                    searchQuery = query,
                    progress = state.searchedCount,
                    total = state.sections.size,
                    navigateUp = navigator::pop,
                    onChangeSearchQuery = { query = it.orEmpty() },
                    onSearch = viewModel::search,
                    // The sources searched are the configured migration targets; the pinned/all
                    // source filter has no meaning here.
                    hideSourceFilter = true,
                    sourceFilter = SearchSourceFilter.All,
                    onChangeSearchFilter = {},
                    onlyShowHasResults = state.onlyShowHasResults,
                    onToggleResults = viewModel::toggleOnlyResults,
                    isBusy = state.resolvingPick,
                )
                // The searched title leads, so a tap can always go back to it.
                val alternatives = entry.alternativeTitles
                if (alternatives.isNotEmpty()) {
                    MigrationSearchTitleChips(listOf(entry.title) + alternatives) {
                        query = it
                        viewModel.search(it)
                    }
                }
            }
        },
    ) { contentPadding ->
        // Unlike global search, a source still loading or failed stays under the filter (see
        // hasSomethingToSay): the user needs to tell "could not answer" from "answered nothing".
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
        // Over every searched source, not the filtered few, so a label does not come and go.
        val showsFormat = state.sections.showsFormat
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            items(items = sections, key = { it.sourceKey }) { section ->
                MigrationCandidateStrip(
                    strip = section,
                    showsFormat = showsFormat,
                    isCurrentSource = section.sourceKey == entry.sourceKey,
                    onPick = viewModel::pick,
                    onPreview = { it.openDetails(opener) },
                    onBrowseSource = {
                        if (!openDeepPicker(navigator, entry, section.sourceKey, query, flowId)) {
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
                target.openDetails(opener)
            },
            onFinished = { replaced, resolved ->
                viewModel.dismissDialog()
                // Leave the whole flow behind before landing, so back does not return to
                // results (or a stale config step) for a migration that already happened.
                navigator.popTo(::isOutsideMigrationFlow)
                // The resolved candidate, not the raw pick: replace-detection needs the
                // stored row behind it to swap out the migrated-away details page.
                resolved.openDetailsAfterCommit(navigator, replaced, migrated = entry)
            },
        )
    }
}

/** The titles the entry goes by, each replacing the query and searching on a tap. */
@Composable
internal fun MigrationSearchTitleChips(titles: List<String>, onSearch: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(items = titles, key = { it }) { title ->
            SuggestionChip(
                onClick = { onSearch(title) },
                label = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
    private val sourcePreferences: SourcePreferences,
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

    private val permits = Semaphore(SOURCE_SEARCH_CONCURRENCY)

    @Volatile
    private var searchJob: Job? = null

    // One key: the screen has one entry, so every pick on it competes with every other.
    private val picks = LatestPickGate<Unit>()

    init {
        viewModelScope.launch(io) {
            adapter.prepare()
            val entry = adapter.loadEntries(listOf(entryId)).firstOrNull()
            state.update { it.copy(isLoading = false, entry = entry) }
            if (entry != null) search(entry.title)
        }
        viewModelScope.launch(io) {
            sourcePreferences.globalSearchFilterState.changes().collectLatest { onlyHasResults ->
                state.update { it.copy(onlyShowHasResults = onlyHasResults) }
            }
        }
    }

    /**
     * Search every chosen source for [query]. A new search cancels the one before it, whose late
     * results [fanOutCandidates] then drops.
     */
    fun search(query: String) {
        val entry = state.value.entry ?: return
        if (query.isBlank()) return
        val fullQuery = query.withExtraQuery(extraQuery)
        searchJob?.cancel()
        searchJob = viewModelScope.launch(io) {
            val sources = adapter.sourcesFor()
            state.update { it.copy(sections = sources.map { source -> source.loadingStrip() }) }
            adapter.fanOutCandidates(
                entry = entry,
                query = fullQuery,
                sources = sources,
                permits = permits,
            ) { sourceKey, landed ->
                state.update { it.copy(sections = it.sections.withResult(sourceKey, landed)) }
            }
        }
    }

    /**
     * Take a picked target through [resolvePick]: open the migrate dialog on it, or say why it was
     * refused. The dialog is held in model state, not the composition, so a rotation mid-migrate
     * keeps it alive to receive its result. Picks follow [LatestPickGate]'s rule.
     */
    fun pick(target: MigrationCandidate) {
        val ticket = picks.begin(Unit, target) ?: return
        viewModelScope.launch(io) { resolve(ticket, target) }
    }

    private suspend fun resolve(ticket: LatestPickGate.Ticket<Unit>, target: MigrationCandidate) {
        state.update { if (picks.isCurrent(ticket)) it.copy(resolvingPick = true) else it }
        when (val pick = adapter.resolvePick(target)) {
            is PendingPick.Ready -> settle(ticket) { it.copy(resolvingPick = false, dialogTarget = pick.candidate) }
            is PendingPick.Rejected -> settle(ticket) { it.copy(resolvingPick = false, pickOutcome = pick.outcome) }
        }
    }

    fun dismissDialog() = state.update { it.copy(dialogTarget = null) }

    /** Global search's preference, shared as upstream shares it, so the chip keeps one setting. */
    fun toggleOnlyResults() {
        sourcePreferences.globalSearchFilterState.toggle()
    }

    /**
     * Take a target picked on a pushed browse screen for this entry. The pick is a stored row, which
     * says nothing about its chapters, so it is resolved like a tapped result, under the same ticket
     * from read back to dialog, so a duplicate delivery is refused at either stage.
     */
    fun takePick(browsed: MatchOverrideEvent) {
        val entry = state.value.entry ?: return
        val ticket = picks.begin(Unit, browsed) ?: return
        viewModelScope.launch(io) {
            // Beginning this pick superseded any still resolving, so its working state ends here.
            when (val read = adapter.takePendingPick(browsed, entry.id)) {
                null -> settle(ticket) { it.copy(resolvingPick = false) }
                is PendingPick.Ready -> resolve(ticket, read.candidate)
                is PendingPick.Rejected -> settle(ticket) { it.copy(resolvingPick = false, pickOutcome = read.outcome) }
            }
        }
    }

    /** Apply [change] only if [ticket] is still the current pick, then let the pick go. */
    private fun settle(ticket: LatestPickGate.Ticket<Unit>, change: (State) -> State) {
        state.update { if (picks.isCurrent(ticket)) change(it) else it }
        picks.end(ticket)
    }

    /** Called once the screen has shown the outcome; see [PickOutcome]. */
    fun consumePickOutcome() = state.update { it.copy(pickOutcome = null) }

    data class State(
        val isLoading: Boolean = true,
        val entry: MigrationEntry? = null,
        val sections: List<SourceStrip> = emptyList(),
        val dialogTarget: MigrationCandidate? = null,
        /** A picked target is being resolved before its dialog opens; see [pick]. */
        val resolvingPick: Boolean = false,
        /** Consume-once: see [PickOutcome]. */
        val pickOutcome: PickOutcome? = null,
        val onlyShowHasResults: Boolean = false,
    ) {
        val searchedCount: Int get() = sections.count { it.result !is StripResult.Loading }
    }
}
