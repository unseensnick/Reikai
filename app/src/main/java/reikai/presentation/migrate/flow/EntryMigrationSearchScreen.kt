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
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.library.ContentType
import reikai.presentation.browse.EntrySearchSourceFilterChips
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { EntryMigrationSearchScreenModel(contentType, entryId) }
        val state by screenModel.state.collectAsState()
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
            if (!state.isLoading) screenModel.collectPendingPick()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                // The global-search header shape: search field in the toolbar, progress under it,
                // then the has-results chip. Same surface, same reading.
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Box {
                        SearchToolbar(
                            searchQuery = query,
                            onChangeSearchQuery = { query = it.orEmpty() },
                            onSearch = { screenModel.search(it) },
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
                        onToggleResults = screenModel::toggleOnlyResults,
                    )
                }
            },
        ) { contentPadding ->
            val sections = if (state.onlyShowHasResults) {
                state.sections.filter { it.loading || it.candidates.isNotEmpty() }
            } else {
                state.sections
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(items = sections, key = { it.sourceKey }) { section ->
                    MigrationCandidateStrip(
                        sourceName = section.sourceName,
                        sourceLang = section.sourceLang,
                        candidates = section.candidates,
                        error = section.error,
                        onPick = screenModel::showDialog,
                        onPreview = { it.openDetails(navigator) },
                        onBrowseSource = {
                            if (!openDeepPicker(navigator, entry, section.sourceKey, query)) {
                                context.toast(MR.strings.internal_error)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaterialTheme.padding.small),
                        loading = section.loading,
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
                onDismissRequest = screenModel::dismissDialog,
                // Show opens the candidate, not the entry: the target is the thing being decided on,
                // and the entry is the one the user already knows.
                onShowEntry = {
                    screenModel.dismissDialog()
                    target.openDetails(navigator)
                },
                onFinished = { replaced, resolved ->
                    screenModel.dismissDialog()
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

class EntryMigrationSearchScreenModel(
    contentType: ContentType,
    private val entryId: Long,
) : StateScreenModel<EntryMigrationSearchScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    private val pickHandoff: MigrationPickHandoff = Injekt.get()

    private val permits = Semaphore(SEARCH_CONCURRENCY)

    @Volatile
    private var searchJob: Job? = null

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val entry = adapter.loadEntries(listOf(entryId)).firstOrNull()
            mutableState.update { it.copy(isLoading = false, entry = entry) }
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
        val sources = sourcesFor()
        searchJob?.cancel()
        mutableState.update { state ->
            state.copy(sections = sources.map { Section(it.key, it.name, it.lang, loading = true) })
        }
        searchJob = screenModelScope.launchIO {
            val myJob = coroutineContext[Job]
            coroutineScope {
                sources.map { source ->
                    async {
                        val result = permits.withPermit {
                            runCatchingCancellable { adapter.candidates(entry, query, source.key) }
                        }
                        if (searchJob !== myJob) return@async
                        mutableState.update { state ->
                            state.copy(
                                sections = state.sections.map { section ->
                                    if (section.sourceKey != source.key) {
                                        section
                                    } else {
                                        section.copy(
                                            loading = false,
                                            candidates = result.getOrDefault(emptyList()),
                                            error = result.exceptionOrNull()
                                                ?.let { it.message ?: it.javaClass.simpleName },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** The chosen sources, resolved against what is enabled. The empty-selection fallback is
     *  pinned-only, mirroring the config screen's seed, so what it showed is what gets searched. */
    private fun sourcesFor(): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val byKey = enabled.associateBy { it.key }
        return adapter.savedSelection().mapNotNull { byKey[it] }.ifEmpty {
            val pinned = adapter.pinnedKeys()
            enabled.filter { it.key in pinned }.ifEmpty { enabled }
        }
    }

    override fun onDispose() {
        super.onDispose()
        // An uncollected pick belongs to this migration only.
        pickHandoff.clear()
    }

    /** Held in model state, not the composition, so a rotation mid-migrate keeps the dialog alive
     *  to receive its result. */
    fun showDialog(target: MigrationCandidate) = mutableState.update { it.copy(dialogTarget = target) }

    fun dismissDialog() = mutableState.update { it.copy(dialogTarget = null) }

    fun toggleOnlyResults() = mutableState.update { it.copy(onlyShowHasResults = !it.onlyShowHasResults) }

    /**
     * Open the migrate dialog for a target picked on a pushed browse screen, if one came back for
     * this entry. Called when the screen returns to the foreground; the pick is a stored row
     * already, so it wraps into a resolved candidate with no search.
     */
    fun collectPendingPick() {
        val entry = state.value.entry ?: return
        screenModelScope.launchIO {
            val targetRawId = pickHandoff.take(entry.id) ?: return@launchIO
            // The entry itself is never a target: the engines would no-op.
            if (targetRawId == entry.id.rawId) return@launchIO
            val candidate = runCatchingCancellable { adapter.storedCandidate(targetRawId) }.getOrNull()
                ?: return@launchIO
            mutableState.update { it.copy(dialogTarget = candidate) }
        }
    }

    data class Section(
        val sourceKey: String,
        val sourceName: String,
        /** Raw language tag, localized at render (shared header shows it like global search). */
        val sourceLang: String = "",
        val loading: Boolean = false,
        val candidates: List<MigrationCandidate> = emptyList(),
        val error: String? = null,
    )

    data class State(
        val isLoading: Boolean = true,
        val entry: MigrationEntry? = null,
        val sections: List<Section> = emptyList(),
        val dialogTarget: MigrationCandidate? = null,
        val onlyShowHasResults: Boolean = false,
    ) {
        val searchedCount: Int get() = sections.count { !it.loading }
    }
}
