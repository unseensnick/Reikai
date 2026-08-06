package reikai.presentation.migrate.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.NovelSourceIcon
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Choose which sources a migration searches, and in what order. Shared by both content types over
 * [MigrationFlowAdapter]; only the adapter knows what a source is.
 *
 * Continue leads to the migration list. The search options are asked for here too, before any row
 * exists, which is what keeps the list free of the machinery a mid-search change would need.
 */
class EntryMigrationConfigScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            EntryMigrationConfigScreenModel(migrationAdapterFor(contentType))
        }
        val state by screenModel.state.collectAsState()
        val listState = rememberLazyListState()
        var showTuning by rememberSaveable { mutableStateOf(false) }

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.action_migrate),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationFlow_searchOptionsTitle),
                                    icon = Icons.Outlined.Tune,
                                    onClick = { showTuning = true },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                                    onClick = { screenModel.selectAll() },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                                    onClick = { screenModel.selectNone() },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                                    onClick = { screenModel.selectPinned() },
                                ),
                            ),
                        )
                    },
                )
            },
            floatingActionButton = {
                // Hidden rather than disabled on an empty selection: continuing would search every
                // enabled source, which is not what an empty selection asks for.
                if (state.selected.isNotEmpty()) {
                    SmallExtendedFloatingActionButton(
                        text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                        icon = {},
                        onClick = {
                            // One entry has nothing to accept in bulk and no progress worth a list,
                            // so it goes straight to its results. The extra query travels with the
                            // screen rather than through the adapter: it belongs to this run only,
                            // and no preference should remember it for the next one.
                            val extraQuery = state.tuning.extraQuery
                            val next = entryIds.singleOrNull()
                                ?.let { EntryMigrationSearchScreen(contentType, it, extraQuery) }
                                ?: EntryMigrationListScreen(contentType, entryIds, extraQuery)
                            // Replace, never push, as upstream does at this same point. Every step of
                            // the flow replaces itself, so back from the results leaves the migration
                            // entirely and returns to whatever opened it; changing the target sources
                            // means entering Migrate again rather than stepping back.
                            navigator.replace(next)
                        },
                    )
                }
            },
        ) { contentPadding ->
            val reorderState = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
                screenModel.reorder(from.key, to.key)
            }
            // The language pill only earns its place when it disambiguates anything.
            val showLanguage = (state.selected + state.available).distinctBy { it.lang }.size > 1
            FastScrollLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = contentPadding,
            ) {
                if (state.selected.isNotEmpty()) {
                    item(key = "selected-header") {
                        SectionHeader(stringResource(MR.strings.migrationConfigScreen_selectedHeader))
                    }
                    itemsIndexed(state.selected, key = { _, source -> source.key }) { index, source ->
                        SourceItemContainer(
                            firstItem = index == 0,
                            lastItem = index == state.selected.size - 1,
                            source = source,
                            showLanguage = showLanguage,
                            // Order only means something with more than one source in it.
                            dragEnabled = state.selected.size > 1,
                            state = reorderState,
                            key = source.key,
                            onClick = { screenModel.toggleSelection(source.key) },
                        )
                    }
                }
                if (state.available.isNotEmpty()) {
                    item(key = "available-header") {
                        SectionHeader(stringResource(MR.strings.migrationConfigScreen_availableHeader))
                    }
                    itemsIndexed(state.available, key = { _, source -> "available-${source.key}" }) { index, source ->
                        SourceItemContainer(
                            firstItem = index == 0,
                            lastItem = index == state.available.size - 1,
                            source = source,
                            showLanguage = showLanguage,
                            dragEnabled = false,
                            state = reorderState,
                            key = "available-${source.key}",
                            onClick = { screenModel.toggleSelection(source.key) },
                        )
                    }
                }
            }
        }

        if (showTuning) {
            // The query draft is hosted here rather than inside the sheet so a rotation neither
            // loses it nor commits a half-typed one; only IME done and a real dismissal commit it.
            var tuningQuery by rememberSaveable(state.tuning.extraQuery) {
                mutableStateOf(state.tuning.extraQuery.orEmpty())
            }
            val commitQuery = {
                val trimmed = tuningQuery.trim().takeIf(String::isNotBlank)
                if (trimmed != state.tuning.extraQuery) {
                    screenModel.applyTuning(state.tuning.copy(extraQuery = trimmed))
                }
            }
            AdaptiveSheet(
                onDismissRequest = {
                    commitQuery()
                    showTuning = false
                },
            ) {
                MigrationTuningSheet(
                    tuning = state.tuning,
                    query = tuningQuery,
                    onQueryChange = { tuningQuery = it },
                    onCommitQuery = commitQuery,
                    matchStrategy = screenModel.matchStrategy,
                    onApply = screenModel::applyTuning,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(MaterialTheme.padding.medium),
    )
}

/** The card-group row shape of Mihon's MigrationConfigScreen: rounded ends on each group, dividers
 *  between rows, tap toggles the source between the lists. */
@Composable
private fun LazyItemScope.SourceItemContainer(
    firstItem: Boolean,
    lastItem: Boolean,
    source: MigrationSourceUi,
    showLanguage: Boolean,
    dragEnabled: Boolean,
    state: ReorderableLazyListState,
    key: Any,
    onClick: () -> Unit,
) {
    val shape = remember(firstItem, lastItem) {
        val top = if (firstItem) 12.dp else 0.dp
        val bottom = if (lastItem) 12.dp else 0.dp
        RoundedCornerShape(top, top, bottom, bottom)
    }

    ReorderableItem(
        state = state,
        key = key,
        enabled = dragEnabled,
    ) { _ ->
        ElevatedCard(
            shape = shape,
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .animateItem(),
        ) {
            SourceItem(
                source = source,
                showLanguage = showLanguage,
                dragEnabled = dragEnabled,
                scope = this@ReorderableItem,
                onClick = onClick,
            )
        }
    }

    if (!lastItem) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
    }
}

@Composable
private fun SourceItem(
    source: MigrationSourceUi,
    showLanguage: Boolean,
    dragEnabled: Boolean,
    scope: ReorderableCollectionItemScope,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = if (dragEnabled) {
            {
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = null,
                    modifier = with(scope) {
                        Modifier.draggableHandle()
                    },
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceRowIcon(icon = source.icon)
            Text(
                text = source.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (showLanguage && source.lang.isNotEmpty()) {
                Pill(
                    // Manga langs are short tags ("en" -> "EN"); novel plugins declare full names
                    // ("English"), which stay as-is rather than becoming a shouting pill.
                    text = if (source.lang.length <= 6) {
                        LocaleHelper.getShortDisplayName(source.lang, uppercase = true)
                    } else {
                        source.lang
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SourceRowIcon(icon: MigrationSourceIcon) {
    when (icon) {
        is MigrationSourceIcon.MangaSource -> SourceIcon(source = icon.source, modifier = Modifier.size(32.dp))
        is MigrationSourceIcon.NovelUrl -> NovelSourceIcon(iconUrl = icon.iconUrl, size = 32.dp)
    }
}

class EntryMigrationConfigScreenModel(
    // Injected rather than resolved here, as the list model's are: the source seed and the order
    // writes are otherwise reachable only by reading the code. The screen resolves and passes them.
    private val adapter: MigrationFlowAdapter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StateScreenModel<EntryMigrationConfigScreenModel.State>(State()) {

    val matchStrategy: MatchStrategy get() = adapter.matchStrategy

    /** Serializes the order writes; see [editSelection]. */
    private val persistLock = Mutex()

    /** Versions them too, so a superseded write is dropped rather than saved after the one that
     *  replaced it. Assigned on the caller's thread, like the confirm scan's id. */
    @Volatile
    private var selectionVersion = 0

    init {
        screenModelScope.launch(io) {
            adapter.prepare()
            val enabled = adapter.enabledSources()
            val saved = adapter.savedSelection()
            val pinned = adapter.pinnedKeys()
            val byKey = enabled.associateBy { it.key }
            // Saved order first, else the pinned sources, else everything enabled: upstream's three
            // tiers, and the same ladder sourcesFor() searches under. Stopping at pinned left a
            // profile with nothing pinned and no saved selection looking at an empty screen with the
            // Continue button hidden, while the search layer would have used every enabled source.
            val selected = saved.mapNotNull { byKey[it] }
                .ifEmpty { enabled.filter { it.key in pinned } }
                .ifEmpty { enabled }
            val selectedKeys = selected.mapTo(HashSet()) { it.key }
            mutableState.update {
                it.copy(
                    isLoading = false,
                    selected = selected,
                    available = enabled.filterNot { source -> source.key in selectedKeys },
                    tuning = adapter.readTuning().normalizedFor(adapter.matchStrategy),
                )
            }
        }
    }

    /**
     * Save the search options. They are asked for here, before the list exists, so nothing can
     * change what a search returns while one is running: the list reads them once and never rebuilds
     * a row underneath live work.
     *
     * Normalized on the way in rather than trusted from the sheet, so an option this content type
     * cannot run is never persisted.
     */
    fun applyTuning(edited: MigrationTuning) {
        val tuning = edited.normalizedFor(adapter.matchStrategy)
        mutableState.update { it.copy(tuning = tuning) }
        screenModelScope.launch(io) { adapter.persistTuning(tuning) }
    }

    fun toggleSelection(key: String) = editSelection { state ->
        val selected = state.selected.toMutableList()
        val available = state.available.toMutableList()
        val fromSelected = selected.indexOfFirst { it.key == key }
        if (fromSelected >= 0) {
            available += selected.removeAt(fromSelected)
        } else {
            val fromAvailable = available.indexOfFirst { it.key == key }
            if (fromAvailable < 0) return@editSelection state
            selected += available.removeAt(fromAvailable)
        }
        state.copy(
            selected = selected,
            available = available.sortedBy { it.name.lowercase() },
        )
    }

    fun reorder(fromKey: Any?, toKey: Any?) = editSelection { state ->
        val from = state.selected.indexOfFirst { it.key == fromKey }
        val to = state.selected.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0) return@editSelection state
        val reordered = state.selected.toMutableList().apply { add(to, removeAt(from)) }
        state.copy(selected = reordered)
    }

    fun selectAll() = editSelection { state ->
        state.copy(selected = state.selected + state.available, available = emptyList())
    }

    fun selectNone() = editSelection { state ->
        val all = (state.selected + state.available).sortedBy { it.name.lowercase() }
        state.copy(selected = emptyList(), available = all)
    }

    fun selectPinned() = editSelection { state ->
        val pinned = adapter.pinnedKeys()
        val all = state.selected + state.available
        state.copy(
            selected = all.filter { it.key in pinned },
            available = all.filterNot { it.key in pinned }.sortedBy { it.name.lowercase() },
        )
    }

    /**
     * Apply a selection edit, then save the settled order off the caller's thread.
     *
     * A state update re-runs its block when a write races it (the init load is the one that can), so
     * a save inside the block repeats. The writes are serialized and versioned instead: two quick
     * edits used to race, and the older order could land last and be what the flow then searched.
     */
    private fun editSelection(edit: (State) -> State) {
        val settled = mutableState.updateAndGet(edit)
        val version = ++selectionVersion
        screenModelScope.launch(io) {
            persistLock.withLock {
                if (version == selectionVersion) persist(settled)
            }
        }
    }

    /**
     * Persist the order, keeping saved sources that are not on screen in their existing slots. A
     * source that is currently disabled or uninstalled is not listed here, and appending it on every
     * save would walk it to the back of the priority order for no reason the user can see.
     *
     * Reads the on-screen sources from the state it was handed, never a live one: a later edit
     * landing mid-write would otherwise decide which sources this write treats as hidden.
     */
    private fun persist(settled: State) {
        val selected = settled.selected
        val visible = (settled.selected + settled.available).mapTo(HashSet()) { it.key }
        val hidden = adapter.savedSelection().filterTo(HashSet()) { it !in visible }
        if (hidden.isEmpty()) {
            adapter.persistSelection(selected.map { it.key })
            return
        }
        val queue = ArrayDeque(selected.map { it.key })
        val merged = mutableListOf<String>()
        adapter.savedSelection().forEach { key ->
            when {
                key in hidden -> merged += key
                queue.isNotEmpty() -> merged += queue.removeFirst()
            }
        }
        merged += queue
        adapter.persistSelection(merged.distinct())
    }

    data class State(
        val isLoading: Boolean = true,
        val selected: List<MigrationSourceUi> = emptyList(),
        val available: List<MigrationSourceUi> = emptyList(),
        val tuning: MigrationTuning = MigrationTuning(),
    )
}
