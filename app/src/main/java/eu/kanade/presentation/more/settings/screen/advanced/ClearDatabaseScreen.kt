package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.SelectAll
import reikai.domain.novel.NovelRepository
import reikai.domain.source.isInstalled
import reikai.domain.source.sourceVisualName
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.components.NovelSourceIcon
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
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

class ClearDatabaseScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ClearDatabaseViewModel>()
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        when (val s = state) {
            is ClearDatabaseViewModel.State.Loading -> LoadingScreen()
            is ClearDatabaseViewModel.State.Ready -> {
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
                        onDismissRequest = viewModel::hideConfirmation,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    scope.launchUI {
                                        viewModel.removeMangaBySourceId(keepReadManga)
                                        viewModel.clearSelection()
                                        viewModel.hideConfirmation()
                                        context.toast(MR.strings.clear_database_completed)
                                    }
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::hideConfirmation) {
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
                                                icon = MaterialSymbols.Rounded.SelectAll,
                                                onClick = viewModel::selectAll,
                                            ),
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_inverse),
                                                icon = MaterialSymbols.Rounded.FlipToBack,
                                                onClick = viewModel::invertSelection,
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
                            onClickAction = viewModel::showConfirmation,
                        ) {
                            // RK --> section headers only when both content types are present
                            val showHeaders = s.items.isNotEmpty() && s.novelItems.isNotEmpty()
                            if (showHeaders) {
                                item { ClearDatabaseSectionHeader(stringResource(MR.strings.content_type_manga)) }
                            }
                            // RK <--
                            items(s.items) { sourceWithCount ->
                                ClearDatabaseItem(
                                    // RK --> the row takes a name and an icon, so novel rows share it
                                    name = sourceWithCount.source.visualName,
                                    icon = { SourceIcon(source = sourceWithCount.source) },
                                    // RK <--
                                    count = sourceWithCount.count,
                                    isSelected = s.selection.contains(sourceWithCount.id),
                                    onClickSelect = { viewModel.toggleSelection(sourceWithCount.source) },
                                )
                            }
                            // RK --> novel sources with non-library rows
                            if (showHeaders) {
                                item { ClearDatabaseSectionHeader(stringResource(MR.strings.content_type_novels)) }
                            }
                            items(s.novelItems) { novelSource ->
                                ClearDatabaseItem(
                                    name = sourceVisualName(novelSource.name, novelSource.lang),
                                    icon = {
                                        NovelSourceIcon(iconUrl = novelSource.iconUrl, missing = !novelSource.isInstalled)
                                    },
                                    count = novelSource.count,
                                    isSelected = s.novelSelection.contains(novelSource.id),
                                    onClickSelect = { viewModel.toggleNovelSelection(novelSource.id) },
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
        // RK -->
        name: String,
        icon: @Composable () -> Unit,
        // RK <--
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
            icon() // RK
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = name, // RK
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

    // RK --> the novel section's subheader
    @Composable
    private fun ClearDatabaseSectionHeader(label: String) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    // RK <--
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ClearDatabaseViewModel(
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val getSourcesWithNonLibraryManga: GetSourcesWithNonLibraryManga,
    // RK -->
    private val novelRepository: NovelRepository,
    private val novelSourceManager: NovelSourceManager,
    // RK <--
) : ViewModel() {

    val state: StateFlow<ClearDatabaseViewModel.State>
        field = MutableStateFlow<ClearDatabaseViewModel.State>(State.Loading)

    init {
        viewModelScope.launchIO {
            // RK --> fold the novel-side source counts into the same Ready state. Load the registry
            //     first: it is empty until something asks, and a cold open lands here with no icons.
            novelSourceManager.ensureLoaded()
            combine(
                getSourcesWithNonLibraryManga.subscribe(),
                novelRepository.getSourcesWithNonLibraryNovelAsFlow(),
            ) { mangaSources, novelSources -> mangaSources to novelSources }
                .collectLatest { (list, novelList) ->
                    val novelItems = novelList
                        .map { (sourceId, count) ->
                            val identity = novelSourceManager.identityOf(sourceId)
                            NovelSourceWithCount(
                                id = sourceId,
                                name = identity.name,
                                iconUrl = identity.iconUrl,
                                lang = identity.lang.orEmpty(),
                                count = count,
                                isInstalled = novelSourceManager.isInstalled(sourceId),
                            )
                        }
                        .sortedBy { it.name }
                    state.update { old ->
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
        // RK --> guarded: a novel-only selection reaches here with no manga selected, and SQLDelight
        // renders an empty collection as `IN ()`, which SQLite rejects, crashing the whole clear.
        if (state.selection.isNotEmpty()) {
            mangaRepository.deleteNonLibraryManga(state.selection, keepReadManga)
        }
        // RK <--
        historyRepository.deleteResetHistory()
        // RK --> novel side of the clear; the keep-read toggle covers both content types
        if (state.novelSelection.isNotEmpty()) {
            novelRepository.deleteNonLibraryNovels(state.novelSelection, keepReadManga)
        }
        // RK <--
    }

    // RK --> one membership toggle for the manga and the novel selection
    private fun <T> List<T>.toggled(item: T): List<T> = if (item in this) this - item else this + item

    fun toggleNovelSelection(id: String) = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(novelSelection = state.novelSelection.toggled(id))
    }
    // RK <--

    fun toggleSelection(source: Source) = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = state.selection.toggled(source.id)) // RK: the shared toggle above
    }

    fun clearSelection() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy( // RK: split to carry novelSelection
            selection = emptyList(),
            // RK -->
            novelSelection = emptyList(),
            // RK <--
        )
    }

    fun selectAll() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy( // RK: split to carry novelSelection
            selection = state.items.fastMap { it.id },
            // RK -->
            novelSelection = state.novelItems.fastMap { it.id },
            // RK <--
        )
    }

    fun invertSelection() = state.update { state ->
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

    fun showConfirmation() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = state.update { state ->
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

    // RK --> display row for a novel source with its non-favorite count; an uninstalled source keeps
    // the name it was last seen with (NovelSourceManager.identityOf) and shows the missing-source icon
    @Immutable
    data class NovelSourceWithCount(
        val id: String,
        val name: String,
        val iconUrl: String?,
        val lang: String,
        val count: Long,
        val isInstalled: Boolean,
    )
    // RK <--
}
