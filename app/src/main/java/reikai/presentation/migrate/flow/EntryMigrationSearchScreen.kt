package reikai.presentation.migrate.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
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

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = entry.title,
                    subtitle = "${state.searchedCount} / ${state.sections.size}",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "query") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(text = stringResource(MR.strings.action_search)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.padding.medium),
                        trailingIcon = {
                            IconButton(
                                onClick = { screenModel.search(query) },
                                enabled = query.isNotBlank(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(MR.strings.action_search),
                                )
                            }
                        },
                    )
                }
                items(items = state.sections, key = { it.sourceKey }) { section ->
                    SourceSection(section = section, onPick = screenModel::showDialog)
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
                onShowEntry = {
                    screenModel.dismissDialog()
                    entry.openDetails(navigator)
                },
                onFinished = { replaced, resolved ->
                    screenModel.dismissDialog()
                    // Leave the whole flow behind before landing, so back does not return to
                    // results (or a stale config step) for a migration that already happened.
                    navigator.popUntil { it !is MigrationFlowScreen }
                    // The resolved candidate, not the raw pick: replace-detection needs the
                    // stored row behind it to swap out the migrated-away details page.
                    resolved.openDetailsAfterCommit(navigator, replaced)
                },
            )
        }
    }
}

@Composable
private fun SourceSection(
    section: EntryMigrationSearchScreenModel.Section,
    onPick: (MigrationCandidate) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = section.sourceName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
        )
        when {
            section.loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            // A source that threw says so; "no results" would be a different, wrong answer.
            section.error != null -> Text(
                text = section.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
            section.candidates.isEmpty() -> Text(
                text = stringResource(MR.strings.no_results_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            ) {
                items(items = section.candidates, key = { it.key }) { candidate ->
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .padding(end = 8.dp)
                            .clickable { onPick(candidate) },
                    ) {
                        MangaCover.Book(modifier = Modifier.fillMaxWidth(), data = candidate.cover)
                        Text(
                            text = candidate.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
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
            state.copy(sections = sources.map { Section(it.key, it.name, loading = true) })
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

    /** The chosen sources, resolved against what is enabled; pinned lead when nothing saved resolves. */
    private fun sourcesFor(): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val byKey = enabled.associateBy { it.key }
        return adapter.savedSelection().mapNotNull { byKey[it] }.ifEmpty {
            val pinned = adapter.pinnedKeys()
            enabled.sortedBy { it.key !in pinned }
        }
    }

    /** Held in model state, not the composition, so a rotation mid-migrate keeps the dialog alive
     *  to receive its result. */
    fun showDialog(target: MigrationCandidate) = mutableState.update { it.copy(dialogTarget = target) }

    fun dismissDialog() = mutableState.update { it.copy(dialogTarget = null) }

    data class Section(
        val sourceKey: String,
        val sourceName: String,
        val loading: Boolean = false,
        val candidates: List<MigrationCandidate> = emptyList(),
        val error: String? = null,
    )

    data class State(
        val isLoading: Boolean = true,
        val entry: MigrationEntry? = null,
        val sections: List<Section> = emptyList(),
        val dialogTarget: MigrationCandidate? = null,
    ) {
        val searchedCount: Int get() = sections.count { !it.loading }
    }
}
