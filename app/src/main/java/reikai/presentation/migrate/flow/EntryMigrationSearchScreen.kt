package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
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
        val screenModel = rememberScreenModel {
            EntryMigrationSearchScreenModel(contentType, entryId, extraQuery)
        }
        val state by screenModel.state.collectAsState()
        var dialogTarget by remember { mutableStateOf<MigrationCandidate?>(null) }

        if (state.entry == null) {
            LoadingScreen()
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
                        .fillMaxWidth()
                        .zIndex(1f),
                )
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
                                { openDeepPicker(navigator, it, section.source.key, state.query) }
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
                                else -> EntrySearchCardRow(
                                    entries = section.candidates,
                                    key = { it.stableKey },
                                    toUi = { it.toBrowseUi() },
                                    onClick = { dialogTarget = it },
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
        val target = dialogTarget
        if (entry != null && target != null) {
            EntryMigrateDialog(
                contentType = contentType,
                entry = entry,
                target = target,
                onDismissRequest = { dialogTarget = null },
                onOpenTarget = { target.openDetails(navigator) },
                onFinished = { replaced ->
                    dialogTarget = null
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
     *  old-query results on the new sections. */
    private var searchGeneration = 0

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val entry = adapter.loadEntries(listOf(entryId)).firstOrNull() ?: return@launchIO
            val query = listOfNotNull(entry.title, extraQuery?.takeIf { it.isNotBlank() }).joinToString(" ")
            mutableState.update { it.copy(entry = entry, query = query) }
            search()
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun search() {
        val entry = state.value.entry ?: return
        val query = state.value.query.ifBlank { return }
        val sources = run {
            val enabled = adapter.enabledSources()
            val saved = adapter.savedSelection()
            if (saved.isEmpty()) {
                // No saved selection: pinned sources lead, matching the list's fallback order. The
                // entry's own source stays searchable; the adapter rejects its identical listing.
                val pinned = adapter.pinnedKeys()
                enabled.sortedBy { it.key !in pinned }
            } else {
                val byKey = enabled.associateBy { it.key }
                saved.mapNotNull { byKey[it] }
            }
        }
        val generation = ++searchGeneration
        mutableState.update { st ->
            st.copy(
                progressDone = 0,
                progressTotal = sources.size,
                sections = sources.map { Section(source = it, loading = true) },
            )
        }
        sources.forEach { source ->
            screenModelScope.launchIO {
                val candidates = searchSemaphore.withPermit {
                    runCatchingCancellable { adapter.candidates(entry, query, source.key) }
                        .getOrDefault(emptyList())
                }
                if (generation != searchGeneration) return@launchIO
                mutableState.update { st ->
                    st.copy(
                        progressDone = st.progressDone + 1,
                        sections = st.sections
                            .map {
                                if (it.source.key ==
                                    source.key
                                ) {
                                    it.copy(loading = false, candidates = candidates)
                                } else {
                                    it
                                }
                            }
                            .sortedWith(
                                compareBy(
                                    { it.candidates.isEmpty() && !it.loading },
                                    { it.source.name.lowercase() },
                                ),
                            ),
                    )
                }
            }
        }
    }

    data class Section(
        val source: MigrationSourceUi,
        val loading: Boolean = false,
        val candidates: List<MigrationCandidate> = emptyList(),
    )

    data class State(
        val entry: MigrationEntry? = null,
        val query: String = "",
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val sections: List<Section> = emptyList(),
    )
}
