package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelRepository
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.components.NovelSourceIcon
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.data.Database
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryManga
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ClearDatabaseScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ClearDatabaseScreenModel() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        when (val s = state) {
            is ClearDatabaseScreenModel.State.Loading -> LoadingScreen()
            is ClearDatabaseScreenModel.State.Ready -> {
                if (s.showConfirmation) {
                    var keepReadManga by remember { mutableStateOf(true) }
                    AlertDialog(
                        title = {
                            Text(text = stringResource(MR.strings.are_you_sure))
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                Text(text = stringResource(MR.strings.clear_database_text))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.clear_db_exclude_read),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = keepReadManga,
                                        onCheckedChange = { keepReadManga = it },
                                    )
                                }
                                if (!keepReadManga) {
                                    Text(
                                        text = stringResource(MR.strings.clear_database_history_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        onDismissRequest = model::hideConfirmation,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    scope.launchUI {
                                        model.removeMangaBySourceId(keepReadManga)
                                        model.clearSelection()
                                        model.hideConfirmation()
                                        context.toast(MR.strings.clear_database_completed)
                                    }
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = model::hideConfirmation) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }

                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = stringResource(MR.strings.pref_clear_database),
                            navigateUp = navigator::pop,
                            actions = {
                                // RK --> novel rows are selectable too
                                if (s.items.isNotEmpty() || s.novelItems.isNotEmpty()) {
                                    // RK <--
                                    AppBarActions(
                                        actions = listOf(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_all),
                                                icon = Icons.Outlined.SelectAll,
                                                onClick = model::selectAll,
                                            ),
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_inverse),
                                                icon = Icons.Outlined.FlipToBack,
                                                onClick = model::invertSelection,
                                            ),
                                        ),
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    // RK --> the screen is clean only when both content types have nothing to clear
                    if (s.items.isEmpty() && s.novelItems.isEmpty()) {
                        // RK <--
                        EmptyScreen(
                            message = stringResource(MR.strings.database_clean),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumnWithAction(
                            contentPadding = contentPadding,
                            actionLabel = stringResource(MR.strings.action_delete),
                            // RK -->
                            actionEnabled = s.selection.isNotEmpty() || s.novelSelection.isNotEmpty(),
                            // RK <--
                            onClickAction = model::showConfirmation,
                        ) {
                            // RK --> section headers only when both content types are present
                            val showHeaders = s.items.isNotEmpty() && s.novelItems.isNotEmpty()
                            if (showHeaders) {
                                item { ClearDatabaseSectionHeader(stringResource(MR.strings.content_type_manga)) }
                            }
                            // RK <--
                            items(s.items) { sourceWithCount ->
                                ClearDatabaseItem(
                                    source = sourceWithCount.source,
                                    count = sourceWithCount.count,
                                    isSelected = s.selection.contains(sourceWithCount.id),
                                    onClickSelect = { model.toggleSelection(sourceWithCount.source) },
                                )
                            }
                            // RK --> novel sources with non-library rows
                            if (showHeaders) {
                                item { ClearDatabaseSectionHeader(stringResource(MR.strings.content_type_novels)) }
                            }
                            items(s.novelItems) { novelSource ->
                                ClearDatabaseNovelItem(
                                    item = novelSource,
                                    isSelected = s.novelSelection.contains(novelSource.id),
                                    onClickSelect = { model.toggleNovelSelection(novelSource.id) },
                                )
                            }
                            // RK <--
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ClearDatabaseItem(
        source: Source,
        count: Long,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .clickable(onClick = onClickSelect)
                .padding(horizontal = 8.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(source = source)
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = source.visualName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(MR.strings.clear_database_source_item_count, count))
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        }
    }

    // RK --> novel section pieces: a plain subheader and the novel twin of ClearDatabaseItem
    // (String-keyed source, icon from the plugin registry's CDN URL)
    @Composable
    private fun ClearDatabaseSectionHeader(label: String) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    @Composable
    private fun ClearDatabaseNovelItem(
        item: ClearDatabaseScreenModel.NovelSourceWithCount,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .clickable(onClick = onClickSelect)
                .padding(horizontal = 8.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelSourceIcon(iconUrl = item.iconUrl)
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(MR.strings.clear_database_source_item_count, item.count))
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        }
    }
    // RK <--
}

private class ClearDatabaseScreenModel : StateScreenModel<ClearDatabaseScreenModel.State>(State.Loading) {
    private val getSourcesWithNonLibraryManga: GetSourcesWithNonLibraryManga = Injekt.get()
    private val database: Database = Injekt.get()

    // RK -->
    private val novelRepository: NovelRepository = Injekt.get()
    private val novelSourceManager: NovelSourceManager = Injekt.get()
    // RK <--

    init {
        screenModelScope.launchIO {
            // RK --> fold the novel-side source counts into the same Ready state
            combine(
                getSourcesWithNonLibraryManga.subscribe(),
                novelRepository.getSourcesWithNonLibraryNovelAsFlow(),
            ) { mangaSources, novelSources -> mangaSources to novelSources }
                .collectLatest { (list, novelList) ->
                    val novelItems = novelList
                        .map { (sourceId, count) ->
                            val source = novelSourceManager.get(sourceId)
                            NovelSourceWithCount(
                                id = sourceId,
                                name = source?.name ?: sourceId,
                                iconUrl = source?.iconUrl,
                                count = count,
                            )
                        }
                        .sortedBy { it.name }
                    mutableState.update { old ->
                        val items = list.sortedBy { it.name }
                        when (old) {
                            State.Loading -> State.Ready(items, novelItems)
                            is State.Ready -> old.copy(items = items, novelItems = novelItems)
                        }
                    }
                }
            // RK <--
        }
    }

    suspend fun removeMangaBySourceId(keepReadManga: Boolean) = withNonCancellableContext {
        val state = state.value as? State.Ready ?: return@withNonCancellableContext
        database.mangasQueries.deleteNonLibraryManga(state.selection, keepReadManga.toLong())
        database.historyQueries.removeResettedHistory()
        // RK --> novel side of the clear; the keep-read toggle covers both content types
        if (state.novelSelection.isNotEmpty()) {
            novelRepository.deleteNonLibraryNovels(state.novelSelection, keepReadManga)
        }
        // RK <--
    }

    // RK -->
    fun toggleNovelSelection(id: String) = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        val mutableList = state.novelSelection.toMutableList()
        if (mutableList.contains(id)) {
            mutableList.remove(id)
        } else {
            mutableList.add(id)
        }
        state.copy(novelSelection = mutableList)
    }
    // RK <--

    fun toggleSelection(source: Source) = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        val mutableList = state.selection.toMutableList()
        if (mutableList.contains(source.id)) {
            mutableList.remove(source.id)
        } else {
            mutableList.add(source.id)
        }
        state.copy(selection = mutableList)
    }

    fun clearSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = emptyList(),
            // RK -->
            novelSelection = emptyList(),
            // RK <--
        )
    }

    fun selectAll() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = state.items.fastMap { it.id },
            // RK -->
            novelSelection = state.novelItems.fastMap { it.id },
            // RK <--
        )
    }

    fun invertSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = state.items
                .fastMap { it.id }
                .filterNot { it in state.selection },
            // RK -->
            novelSelection = state.novelItems
                .fastMap { it.id }
                .filterNot { it in state.novelSelection },
            // RK <--
        )
    }

    fun showConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = false)
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val items: List<SourceWithCount>,
            // RK -->
            val novelItems: List<NovelSourceWithCount> = emptyList(),
            // RK <--
            val selection: List<Long> = emptyList(),
            // RK -->
            val novelSelection: List<String> = emptyList(),
            // RK <--
            val showConfirmation: Boolean = false,
        ) : State
    }

    // RK --> display row for a novel source with its non-favorite count; name/icon resolved from
    // the source manager at map time, falling back to the raw plugin id for uninstalled sources
    @Immutable
    data class NovelSourceWithCount(
        val id: String,
        val name: String,
        val iconUrl: String?,
        val count: Long,
    )
    // RK <--
}
