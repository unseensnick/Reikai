package reikai.presentation.library.hidden

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.roundedfilled.Visibility
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.details.detailsRoute
import reikai.presentation.library.LibraryItemsLayout
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** The series hidden from the library view, opened on the [initialContentType] chip the library showed. */
@Serializable
data class HiddenEntriesRoute(
    val initialContentType: ContentType = ContentType.ALL,
) : NavKey

@Composable
fun HiddenEntriesScreen(initialContentType: ContentType) {
    val navigator = LocalNavigator.current
    val viewModel = assistedMetroViewModel<HiddenEntriesViewModel, HiddenEntriesViewModel.Factory> {
        create(initialContentType = initialContentType)
    }
    val state by viewModel.state.collectAsState()
    val displayMode by remember { viewModel.displayMode() }

    val current = state
    if (current == null) {
        LoadingScreen()
        return
    }
    HiddenEntriesContent(
        state = current,
        displayMode = displayMode,
        getColumnsForOrientation = viewModel::columnsForOrientation,
        navigateUp = navigator::pop,
        onSelectContentType = viewModel::setContentType,
        onClick = { item ->
            if (current.selection.isEmpty()) {
                navigator.push(item.entryId.detailsRoute())
            } else {
                viewModel.toggleSelection(item.entryId)
            }
        },
        onLongClick = { viewModel.rangeSelection(it.entryId) },
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onUnhide = viewModel::unhideSelected,
    )
}

@Composable
internal fun HiddenEntriesContent(
    state: HiddenEntriesState,
    displayMode: LibraryDisplayMode,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    navigateUp: () -> Unit,
    onSelectContentType: (ContentType) -> Unit,
    onClick: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onUnhide: () -> Unit,
) {
    // Back leaves selection mode before it leaves the screen, as the library does.
    BackHandler(enabled = state.selection.isNotEmpty(), onBack = onClearSelection)

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_hidden_entries),
                navigateUp = navigateUp,
                actionModeCounter = state.selection.size,
                onCancelActionMode = onClearSelection,
                actionModeActions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = MaterialSymbols.Rounded.SelectAll,
                                onClick = onSelectAll,
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_unhide),
                                icon = MaterialSymbols.RoundedFilled.Visibility,
                                onClick = onUnhide,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        // The bar's padding goes outside the grid, as the library's does, so its scroll thumb starts below it.
        Column(
            modifier = Modifier.padding(
                top = paddingValues.calculateTopPadding(),
                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
            ),
        ) {
            ContentTypeFilterChips(selected = state.contentType, onSelect = onSelectContentType)
            if (state.rows.isEmpty()) {
                EmptyScreen(stringRes = MR.strings.info_empty_hidden_entries, modifier = Modifier.fillMaxSize())
                return@Column
            }
            Box(Modifier.weight(1f)) {
                LibraryItemsLayout(
                    items = state.rows,
                    displayMode = displayMode,
                    getColumnsForOrientation = getColumnsForOrientation,
                    contentPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding()),
                    selection = state.selection,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClickContinueReading = null,
                    searchQuery = null,
                    onGlobalSearchClicked = {},
                )
            }
        }
    }
}
