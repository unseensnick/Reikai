package reikai.presentation.novel.migrate

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.NovelSourceRow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.shouldExpandFAB
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Source-selection pre-step for novel migration, the novel twin of Mihon's
 * [mihon.feature.migration.config.MigrationConfigScreen]. Installed novel sources are split into
 * Selected (drag-reorderable into priority order) and Available; Continue saves the selection and
 * advances to [NovelMigrationListScreen], which then searches only those sources, in that order
 * (so the first selected source's hit becomes the suggested match). See [NovelMigrationConfigScreenModel].
 */
class NovelMigrationConfigScreen(
    private val novelIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelMigrationConfigScreenModel() }
        val state by screenModel.state.collectAsState()

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
                                        screenModel.toggleSelection(NovelMigrationConfigScreenModel.SelectionConfig.All)
                                    },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                                    icon = Icons.Outlined.Deselect,
                                    onClick = {
                                        screenModel.toggleSelection(
                                            NovelMigrationConfigScreenModel.SelectionConfig.None,
                                        )
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                                    onClick = {
                                        screenModel.toggleSelection(
                                            NovelMigrationConfigScreenModel.SelectionConfig.Pinned,
                                        )
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectEnabledLabel),
                                    onClick = {
                                        screenModel.toggleSelection(
                                            NovelMigrationConfigScreenModel.SelectionConfig.Enabled,
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
                        navigator.replace(NovelMigrationListScreen(novelIds))
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            },
        ) { contentPadding ->
            val reorderableState = rememberReorderableLazyListState(lazyListState, contentPadding) { from, to ->
                val fromIndex = selectedSources.indexOfFirst { it.id == from.key }
                val toIndex = selectedSources.indexOfFirst { it.id == to.key }
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
                    items(items = selectedSources, key = { it.id }) { source ->
                        ReorderableItem(reorderableState, key = source.id, enabled = selectedSources.size > 1) {
                            NovelSourceRow(
                                name = source.name,
                                lang = source.lang,
                                iconUrl = source.iconUrl,
                                onClickItem = { screenModel.toggleSelection(source.id) },
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
                    items(items = availableSources, key = { "available-${it.id}" }) { source ->
                        NovelSourceRow(
                            name = source.name,
                            lang = source.lang,
                            iconUrl = source.iconUrl,
                            onClickItem = { screenModel.toggleSelection(source.id) },
                        )
                    }
                }
            }
        }
    }
}

class NovelMigrationConfigScreenModel(
    private val sourceManager: NovelSourceManager = Injekt.get(),
    val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : StateScreenModel<NovelMigrationConfigScreenModel.State>(State()) {

    // Selected first, then by saved priority order, then by name. Selected ids are always in
    // `included`, so the raw indexOf orders them; unselected (-1) are already split off by the first key.
    private fun comparator(included: List<String>) = compareBy<MigrationSource>(
        { !it.isSelected },
        { included.indexOf(it.id) },
        { it.name.lowercase() },
    )

    init {
        screenModelScope.launchIO {
            val pinned = sourcePreferences.pinnedNovelSources.get()
            val disabled = sourcePreferences.disabledNovelSources.get()
            val included = sourcePreferences.novelMigrationSources.get()
            val sources = sourceManager.getAll().map { s ->
                MigrationSource(
                    id = s.id,
                    name = s.name,
                    lang = s.lang,
                    iconUrl = s.iconUrl,
                    isSelected = when {
                        included.isNotEmpty() -> s.id in included
                        pinned.isNotEmpty() -> s.id in pinned
                        else -> s.id !in disabled
                    },
                )
            }
            mutableState.update { it.copy(isLoading = false, sources = sources.sortedWith(comparator(included))) }
        }
    }

    private fun updateSources(action: (List<MigrationSource>) -> List<MigrationSource>) {
        mutableState.update { state ->
            val updated = action(state.sources)
            val included = updated.filter { it.isSelected }.map { it.id }
            state.copy(sources = updated.sortedWith(comparator(included)))
        }
        saveSources()
    }

    fun toggleSelection(id: String) {
        updateSources { sources ->
            sources.map { if (it.id == id) it.copy(isSelected = !it.isSelected) else it }
        }
    }

    fun toggleSelection(config: SelectionConfig) {
        val pinned = sourcePreferences.pinnedNovelSources.get()
        val disabled = sourcePreferences.disabledNovelSources.get()
        val isSelected: (String) -> Boolean = {
            when (config) {
                SelectionConfig.All -> true
                SelectionConfig.None -> false
                SelectionConfig.Pinned -> it in pinned
                SelectionConfig.Enabled -> it !in disabled
            }
        }
        updateSources { sources -> sources.map { it.copy(isSelected = isSelected(it.id)) } }
    }

    fun orderSource(from: Int, to: Int) {
        updateSources { it.toMutableList().apply { add(to, removeAt(from)) }.toList() }
    }

    fun saveSources() {
        val ids = state.value.sources.filter { it.isSelected }.map { it.id }
        sourcePreferences.novelMigrationSources.set(ids)
    }

    data class State(
        val isLoading: Boolean = true,
        val sources: List<MigrationSource> = emptyList(),
    )

    enum class SelectionConfig { All, None, Pinned, Enabled }

    data class MigrationSource(
        val id: String,
        val name: String,
        val lang: String,
        val iconUrl: String?,
        val isSelected: Boolean,
    )
}
