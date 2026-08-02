package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.library.ContentType
import reikai.presentation.browse.EntrySearchCardRow
import reikai.presentation.browse.EntrySearchSection
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The single-entry branch of the count-fork: fans the configured target sources out at once
 * (global-search layout over the shared section + card row leaves) and opens the shared
 * [EntryMigrateDialog] on a result tap. Runs on the flow's own search adapter, never on Mihon's
 * `SearchScreenModel`, which stays global-search-only and synced with upstream.
 */
class EntryMigrationSearchScreen(
    private val contentType: ContentType,
    private val entryId: Long,
    private val extraQuery: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel {
            EntryMigrationSearchScreenModel(contentType, entryId, extraQuery)
        }
        val state by screenModel.state.collectAsState()

        if (state.loadFailed || state.entry == null) {
            // Terminal/loading states keep the toolbar: a chrome-less EmptyScreen left system back
            // as the only way out.
            Scaffold(
                topBar = { scrollBehavior ->
                    AppBar(
                        title = stringResource(MR.strings.action_migrate),
                        navigateUp = navigator::pop,
                        scrollBehavior = scrollBehavior,
                    )
                },
            ) { contentPadding ->
                if (state.loadFailed) {
                    // The entry vanished (deleted mid-flow): say so instead of spinning forever.
                    EmptyScreen(stringRes = MR.strings.internal_error, modifier = Modifier.padding(contentPadding))
                } else {
                    LoadingScreen(modifier = Modifier.padding(contentPadding))
                }
            }
            return
        }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = state.query,
                    onChangeSearchQuery = { screenModel.setQuery(it.orEmpty()) },
                    navigateUp = navigator::pop,
                    placeholderText = stringResource(MR.strings.action_search),
                    onSearch = { screenModel.search() },
                    onClickCloseSearch = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (state.progressTotal > 0 && state.progressDone < state.progressTotal) {
                LinearProgressIndicator(
                    progress = { state.progressDone.toFloat() / state.progressTotal },
                    modifier = Modifier
                        // Below the app bar: the content slot starts at y=0 under it.
                        .padding(top = contentPadding.calculateTopPadding())
                        .fillMaxWidth()
                        .zIndex(1f),
                )
            }
            if (state.sections.isEmpty()) {
                // No target sources at all (empty selection resolving to nothing, or a cold novel
                // plugin host): say so instead of a silent blank screen.
                EmptyScreen(
                    stringRes = MR.strings.source_empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            LazyColumn(contentPadding = contentPadding) {
                state.sections.forEach { section ->
                    item(key = section.source.key) {
                        val entry = state.entry
                        EntrySearchSection(
                            title = section.source.name,
                            subtitle = section.source.lang,
                            // The deep path: full browse of this one source (filters, pagination).
                            onClick = entry?.let {
                                {
                                    if (!openDeepPicker(navigator, it, section.source.key, state.query)) {
                                        context.toast(MR.strings.internal_error)
                                    }
                                }
                            },
                        ) {
                            when {
                                section.loading -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                                section.error != null -> Text(
                                    text = section.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                else -> EntrySearchCardRow(
                                    entries = section.candidates,
                                    key = { it.stableKey },
                                    toUi = { it.toBrowseUi() },
                                    onClick = screenModel::showMigrateDialog,
                                    onLongClick = { it.openDetails(navigator) },
                                    isSelected = { false },
                                )
                            }
                        }
                    }
                }
            }
        }

        val entry = state.entry
        val target = state.dialogTarget
        if (entry != null && target != null) {
            EntryMigrateDialog(
                contentType = contentType,
                entry = entry,
                target = target,
                onDismissRequest = screenModel::dismissMigrateDialog,
                onOpenTarget = { target.openDetails(navigator) },
                onFinished = { replaced ->
                    screenModel.dismissMigrateDialog()
                    // Land on the migrated-to entry; on a replace the origin's now-stale details
                    // screen beneath is swapped out instead of left in the stack.
                    navigator.pop()
                    target.openDetailsAfterCommit(navigator, replaced)
                },
            )
        }
    }
}

private const val SEARCH_CONCURRENCY = 5

class EntryMigrationSearchScreenModel(
    contentType: ContentType,
    private val entryId: Long,
    private val extraQuery: String?,
) : StateScreenModel<EntryMigrationSearchScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    private val searchSemaphore = Semaphore(SEARCH_CONCURRENCY)

    /** Bumped per search run so a superseded run's coroutines drop their writes instead of landing
     *  old-query results on the new sections. Volatile: main-thread writes, IO reads. */
    @Volatile
    private var searchGeneration = 0

    /** The current run's jobs, cancelled by the next run: guard-only superseded jobs kept running
     *  and held semaphore permits through dead network hops, starving the new run. */
    private val searchJobs = mutableListOf<Job>()

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val entry = adapter.loadEntries(listOf(entryId)).firstOrNull()
            if (entry == null) {
                mutableState.update { it.copy(loadFailed = true) }
                return@launchIO
            }
            val query = listOfNotNull(entry.title, extraQuery?.takeIf { it.isNotBlank() }).joinToString(" ")
            mutableState.update { it.copy(entry = entry, query = query) }
            search()
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    /** The dialog target lives in model state (not composable memory) so a rotation mid-migrate
     *  keeps the dialog alive to receive the completion and run the landing navigation. */
    fun showMigrateDialog(target: MigrationCandidate) = mutableState.update { it.copy(dialogTarget = target) }

    fun dismissMigrateDialog() = mutableState.update { it.copy(dialogTarget = null) }

    // Synchronized: called from init's IO coroutine and from main-thread search submits; the job
    // list and generation bump need one writer at a time.
    @Synchronized
    fun search() {
        val entry = state.value.entry ?: return
        val query = state.value.query.ifBlank { return }
        val sources = run {
            val enabled = adapter.enabledSources()
            val saved = adapter.savedSelection()
            val resolved = run {
                val byKey = enabled.associateBy { it.key }
                saved.mapNotNull { byKey[it] }
            }
            // Nothing saved, or nothing saved resolves (all currently disabled): pinned sources
            // lead the enabled set, matching the list's fallback order. The entry's own source
            // stays searchable; the adapter rejects its identical listing.
            val head = resolved.ifEmpty {
                val pinned = adapter.pinnedKeys()
                enabled.sortedBy { it.key !in pinned }
            }
            // An explicit manual search reaches EVERY enabled source (upstream's migrate-search
            // scope: the config selection orders, it does not cap); the batch search alone is
            // bounded by the configured selection.
            val headKeys = head.mapTo(HashSet()) { it.key }
            head + enabled.filterNot { it.key in headKeys }
        }
        val generation = ++searchGeneration
        searchJobs.forEach { it.cancel() }
        searchJobs.clear()
        mutableState.update { st ->
            st.copy(
                progressDone = 0,
                progressTotal = sources.size,
                sections = sources.mapIndexed { index, source ->
                    Section(source = source, priority = index, loading = true)
                },
            )
        }
        sources.forEach { source ->
            searchJobs += screenModelScope.launchIO {
                val result = searchSemaphore.withPermit {
                    runCatchingCancellable { adapter.candidates(entry, query, source.key) }
                }
                if (generation != searchGeneration) return@launchIO
                mutableState.update { st ->
                    st.copy(
                        progressDone = st.progressDone + 1,
                        sections = st.sections
                            .map {
                                if (it.source.key == source.key) {
                                    // A failed source keeps its error visible instead of reading as
                                    // "no results", matching the list's override strips.
                                    it.copy(
                                        loading = false,
                                        candidates = result.getOrDefault(emptyList()),
                                        error = result.exceptionOrNull()?.let { e ->
                                            e.message ?: e.javaClass.simpleName
                                        },
                                    )
                                } else {
                                    it
                                }
                            }
                            // Empty and failed sections sink; otherwise the saved priority order
                            // holds (it defines which source "wins" a migration, so the display
                            // must not re-rank).
                            .sortedWith(
                                compareBy(
                                    { it.candidates.isEmpty() && !it.loading },
                                    { it.priority },
                                ),
                            ),
                    )
                }
            }
        }
    }

    data class Section(
        val source: MigrationSourceUi,
        /** Index in the saved source order, so display sorting never re-ranks the priority. */
        val priority: Int = 0,
        val loading: Boolean = false,
        val candidates: List<MigrationCandidate> = emptyList(),
        val error: String? = null,
    )

    data class State(
        val entry: MigrationEntry? = null,
        val loadFailed: Boolean = false,
        val query: String = "",
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val sections: List<Section> = emptyList(),
        val dialogTarget: MigrationCandidate? = null,
    )
}
