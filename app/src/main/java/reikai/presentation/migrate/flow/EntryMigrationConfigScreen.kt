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
import androidx.compose.runtime.remember
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.NovelSourceIcon
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.launchIO
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
 * Continue leads to the migration list. The search options are not asked for here: they live on the
 * list itself, where their effect is visible, so this screen is only about sources.
 */
class EntryMigrationConfigScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { EntryMigrationConfigScreenModel(contentType) }
        val state by screenModel.state.collectAsState()
        val listState = rememberLazyListState()

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
                            // so it goes straight to its results.
                            val next = entryIds.singleOrNull()
                                ?.let { EntryMigrationSearchScreen(contentType, it) }
                                ?: EntryMigrationListScreen(contentType, entryIds)
                            // Replace, never push: back from the results belongs on the screen that
                            // chose the entries, not on a config step whose answer is already spent.
                            // Matches upstream, which replaces at this same point.
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
    contentType: ContentType,
) : StateScreenModel<EntryMigrationConfigScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = migrationAdapterFor(contentType)

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val enabled = adapter.enabledSources()
            val saved = adapter.savedSelection()
            val pinned = adapter.pinnedKeys()
            val byKey = enabled.associateBy { it.key }
            // Saved order first, then anything enabled that is not in it. With nothing saved, the
            // pinned sources lead, matching what the list itself would search.
            val selected = saved.mapNotNull { byKey[it] }.ifEmpty { enabled.filter { it.key in pinned } }
            val selectedKeys = selected.mapTo(HashSet()) { it.key }
            mutableState.update {
                it.copy(
                    isLoading = false,
                    selected = selected,
                    available = enabled.filterNot { source -> source.key in selectedKeys },
                )
            }
        }
    }

    fun toggleSelection(key: String) = mutableState.update { state ->
        val selected = state.selected.toMutableList()
        val available = state.available.toMutableList()
        val fromSelected = selected.indexOfFirst { it.key == key }
        if (fromSelected >= 0) {
            available += selected.removeAt(fromSelected)
        } else {
            val fromAvailable = available.indexOfFirst { it.key == key }
            if (fromAvailable < 0) return@update state
            selected += available.removeAt(fromAvailable)
        }
        state.copy(
            selected = selected,
            available = available.sortedBy { it.name.lowercase() },
        ).also { persist(it.selected) }
    }

    fun reorder(fromKey: Any?, toKey: Any?) = mutableState.update { state ->
        val from = state.selected.indexOfFirst { it.key == fromKey }
        val to = state.selected.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0) return@update state
        val reordered = state.selected.toMutableList().apply { add(to, removeAt(from)) }
        state.copy(selected = reordered).also { persist(reordered) }
    }

    fun selectAll() = mutableState.update { state ->
        val all = state.selected + state.available
        state.copy(selected = all, available = emptyList()).also { persist(all) }
    }

    fun selectNone() = mutableState.update { state ->
        val all = (state.selected + state.available).sortedBy { it.name.lowercase() }
        state.copy(selected = emptyList(), available = all).also { persist(emptyList()) }
    }

    fun selectPinned() = mutableState.update { state ->
        val pinned = adapter.pinnedKeys()
        val all = state.selected + state.available
        val selected = all.filter { it.key in pinned }
        state.copy(
            selected = selected,
            available = all.filterNot { it.key in pinned }.sortedBy { it.name.lowercase() },
        ).also { persist(selected) }
    }

    /**
     * Persist the order, keeping saved sources that are not on screen in their existing slots. A
     * source that is currently disabled or uninstalled is not listed here, and appending it on every
     * save would walk it to the back of the priority order for no reason the user can see.
     */
    private fun persist(selected: List<MigrationSourceUi>) {
        val visible = (state.value.selected + state.value.available).mapTo(HashSet()) { it.key }
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
    )
}
