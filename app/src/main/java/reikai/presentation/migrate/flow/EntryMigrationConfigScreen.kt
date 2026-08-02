package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.NovelSourceIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.shouldExpandFAB
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The unified source-selection pre-step, one screen for both content types over the
 * [MigrationFlowAdapter] seam: pick and drag-order the target sources, then Continue applies the
 * count-fork (one entry: the single-entry search screen, tuning sheet skipped like Mihon's
 * short-circuit; more: the tuning sheet, then the migration list). Flat rows, enabled sources only,
 * per the design note in docs/dev/plans/content-layer-migrate-surface.md.
 */
class EntryMigrationConfigScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { EntryMigrationConfigScreenModel(contentType) }
        val state by screenModel.state.collectAsState()
        var tuningSheetOpen by rememberSaveable { mutableStateOf(false) }

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        val (selectedSources, availableSources) = state.sources.partition { it.isSelected }

        val lazyListState = rememberLazyListState()
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.action_migrate),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                                    icon = Icons.Outlined.SelectAll,
                                    onClick = {
                                        screenModel.toggleSelection(
                                            EntryMigrationConfigScreenModel.SelectionConfig.All,
                                        )
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                                    icon = Icons.Outlined.Deselect,
                                    onClick = {
                                        screenModel.toggleSelection(
                                            EntryMigrationConfigScreenModel.SelectionConfig.None,
                                        )
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                                    onClick = {
                                        screenModel.toggleSelection(
                                            EntryMigrationConfigScreenModel.SelectionConfig.Pinned,
                                        )
                                    },
                                ),
                            ),
                        )
                    },
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                    icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                    onClick = {
                        screenModel.saveSources()
                        val singleId = entryIds.singleOrNull()
                        if (singleId != null) {
                            // The sheet is skipped on this branch (Mihon's short-circuit), but the
                            // persisted extra query still applies, as it does on the batch branch.
                            navigator.replace(
                                EntryMigrationSearchScreen(contentType, singleId, state.tuning.extraQuery),
                            )
                        } else {
                            tuningSheetOpen = true
                        }
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                    // Nothing selected means nothing to search: hiding Continue makes "select none"
                    // an explicit dead end instead of silently searching every enabled source.
                    modifier = Modifier.animateFloatingActionButton(
                        visible = selectedSources.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (state.sources.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.source_empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            val reorderableState = rememberReorderableLazyListState(lazyListState, contentPadding) { from, to ->
                val fromIndex = selectedSources.indexOfFirst { it.key == from.key }
                val toIndex = selectedSources.indexOfFirst { it.key == to.key }
                if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
                screenModel.orderSource(fromIndex, toIndex)
            }

            FastScrollLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = contentPadding,
            ) {
                if (selectedSources.isNotEmpty()) {
                    item("selected-header") {
                        BrowseSectionHeader(title = stringResource(MR.strings.migrationConfigScreen_selectedHeader))
                    }
                    items(items = selectedSources, key = { it.key }) { source ->
                        ReorderableItem(reorderableState, key = source.key, enabled = selectedSources.size > 1) {
                            MigrationSourceRow(
                                source = source.source,
                                onClick = { screenModel.toggleSelection(source.key) },
                                action = {
                                    Icon(
                                        imageVector = Icons.Outlined.DragHandle,
                                        contentDescription = null,
                                        modifier = with(this@ReorderableItem) { Modifier.draggableHandle() },
                                    )
                                },
                            )
                        }
                    }
                }
                if (availableSources.isNotEmpty()) {
                    item("available-header") {
                        BrowseSectionHeader(title = stringResource(MR.strings.migrationConfigScreen_availableHeader))
                    }
                    items(items = availableSources, key = { "available-${it.key}" }) { source ->
                        MigrationSourceRow(
                            source = source.source,
                            onClick = { screenModel.toggleSelection(source.key) },
                        )
                    }
                }
            }
        }

        if (tuningSheetOpen) {
            MigrationTuningSheet(
                tuning = state.tuning,
                supportsSmartMatch = state.supportsSmartMatch,
                supportsChapterComparison = state.supportsChapterComparison,
                onDismissRequest = { tuningSheetOpen = false },
                onApply = { tuning ->
                    tuningSheetOpen = false
                    screenModel.persistTuning(tuning)
                    navigator.replace(EntryMigrationListScreen(contentType, entryIds, tuning.extraQuery))
                },
            )
        }
    }
}

@Composable
private fun MigrationSourceRow(
    source: MigrationSourceUi,
    onClick: () -> Unit,
    action: @Composable RowScope.() -> Unit = {},
) {
    BaseBrowseItem(
        onClickItem = onClick,
        icon = { MigrationSourceIcon(icon = source.icon) },
        action = action,
        content = {
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                Text(
                    text = source.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (source.lang.isNotEmpty()) {
                    Text(
                        modifier = Modifier.secondaryItemAlpha(),
                        text = LocaleHelper.getSourceDisplayName(source.lang, LocalContext.current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

/** The per-type icon slot: a manga row carries a domain [Source] for [SourceIcon], a novel row its
 *  plugin icon url for [NovelSourceIcon]. */
@Composable
private fun MigrationSourceIcon(icon: Any?) {
    when (icon) {
        is Source -> SourceIcon(source = icon)
        else -> NovelSourceIcon(iconUrl = icon as? String)
    }
}

class EntryMigrationConfigScreenModel(
    contentType: ContentType,
) : StateScreenModel<EntryMigrationConfigScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    // Selected first, then by saved priority order, then by name, then language (same-named sources
    // across languages would otherwise order unstably). Selected keys are always in `included`, so
    // the raw indexOf orders them; unselected (-1) are already split off by the first key.
    private fun comparator(included: List<String>) = compareBy<ConfigSource>(
        { !it.isSelected },
        { included.indexOf(it.key) },
        { it.name.lowercase() },
        { it.source.lang },
    )

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val saved = adapter.savedSelection()
            val pinned = adapter.pinnedKeys()
            val sources = adapter.enabledSources().map { source ->
                ConfigSource(
                    source = source,
                    isSelected = when {
                        saved.isNotEmpty() -> source.key in saved
                        pinned.isNotEmpty() -> source.key in pinned
                        // The list is enabled-only, so the "everything not disabled" default is everything.
                        else -> true
                    },
                )
            }
            mutableState.update {
                it.copy(
                    isLoading = false,
                    sources = sources.sortedWith(comparator(saved)),
                    tuning = adapter.readTuning(),
                    supportsSmartMatch = adapter.supportsSmartMatch,
                    supportsChapterComparison = adapter.suggestsChapterCounts,
                )
            }
        }
    }

    private fun updateSources(action: (List<ConfigSource>) -> List<ConfigSource>) {
        mutableState.update { state ->
            val updated = action(state.sources)
            val included = updated.filter { it.isSelected }.map { it.key }
            state.copy(sources = updated.sortedWith(comparator(included)))
        }
        saveSources()
    }

    fun toggleSelection(key: String) {
        updateSources { sources ->
            sources.map { if (it.key == key) it.copy(isSelected = !it.isSelected) else it }
        }
    }

    fun toggleSelection(config: SelectionConfig) {
        val pinned = adapter.pinnedKeys()
        val isSelected: (String) -> Boolean = {
            when (config) {
                SelectionConfig.All -> true
                SelectionConfig.None -> false
                SelectionConfig.Pinned -> it in pinned
            }
        }
        updateSources { sources -> sources.map { it.copy(isSelected = isSelected(it.key)) } }
    }

    fun orderSource(from: Int, to: Int) {
        updateSources { it.toMutableList().apply { add(to, removeAt(from)) }.toList() }
    }

    fun saveSources() {
        val visibleSelected = state.value.sources.filter { it.isSelected }.map { it.key }
        // Explicit select-none wins: persisting hidden keys alone would leave a selection that
        // resolves to nothing (searched as "everything" by the fallback) while the config renders
        // every source unchecked.
        if (visibleSelected.isEmpty()) {
            adapter.persistSelection(emptyList())
            return
        }
        // Keep saved keys whose source is currently disabled or uninstalled (they are not listed, so
        // merely opening this screen must not prune them from the priority order), AND keep them in
        // their saved slot: appending them shuffled a temporarily disabled source to the back of the
        // priority order on every save. Hidden keys stay put; visible ones fill the remaining slots
        // in their new order; newly selected sources append.
        val visibleKeys = state.value.sources.mapTo(HashSet()) { it.key }
        val saved = adapter.savedSelection()
        val hiddenSaved = saved.filterTo(HashSet()) { it !in visibleKeys }
        if (hiddenSaved.isEmpty()) {
            adapter.persistSelection(visibleSelected)
            return
        }
        val queue = ArrayDeque(visibleSelected)
        val merged = mutableListOf<String>()
        for (key in saved) {
            when {
                key in hiddenSaved -> merged += key
                queue.isNotEmpty() -> merged += queue.removeFirst()
            }
        }
        merged += queue
        adapter.persistSelection(merged.distinct())
    }

    fun persistTuning(tuning: MigrationTuning) = adapter.persistTuning(tuning)

    data class State(
        val isLoading: Boolean = true,
        val sources: List<ConfigSource> = emptyList(),
        val tuning: MigrationTuning = MigrationTuning(),
        val supportsSmartMatch: Boolean = false,
        val supportsChapterComparison: Boolean = false,
    )

    enum class SelectionConfig { All, None, Pinned }

    data class ConfigSource(
        val source: MigrationSourceUi,
        val isSelected: Boolean,
    ) {
        val key: String inline get() = source.key
        val name: String inline get() = source.name
    }
}
