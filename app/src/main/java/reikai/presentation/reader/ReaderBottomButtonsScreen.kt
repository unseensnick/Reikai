package reikai.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DragHandle
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.presentation.more.settings.Preference as SettingsPreference

/**
 * Which buttons one reader's bottom bar shows, and in what order. One screen serves both readers, each
 * editing its own buttons. The Settings gear is not listed: it is always shown, last.
 */
data class ReaderBottomButtonsScreen(private val scope: ReaderBottomButton.Scope) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_reader_bottom_buttons),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            ReaderBottomButtonsList(scope, contentPadding = paddingValues)
        }
    }
}

/** The reader's own way in: the same list in a sheet over the page, so the bar changes as it is edited. */
@Composable
fun ReaderBottomButtonsDialog(scope: ReaderBottomButton.Scope, onDismissRequest: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Text(
                text = stringResource(MR.strings.pref_reader_bottom_buttons),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(MaterialTheme.padding.medium),
            )
            ReaderBottomButtonsList(
                scope = scope,
                contentPadding = PaddingValues(bottom = MaterialTheme.padding.medium),
                modifier = Modifier.heightIn(max = 480.dp),
            )
        }
    }
}

/** Every button [scope] offers, switched on or off and dragged into order. */
@Composable
private fun ReaderBottomButtonsList(
    scope: ReaderBottomButton.Scope,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel = assistedMetroViewModel<ReaderBottomButtonsViewModel, ReaderBottomButtonsViewModel.Factory>(
        key = scope.name,
    ) {
        create(scope)
    }
    val rows by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Held here while a drag runs, so the list moves under the finger without a write per step.
    val items = remember(rows) { rows.toMutableStateList() }
    var didDrag by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
        val fromIndex = items.indexOfFirst { it.button == from.key }
        val toIndex = items.indexOfFirst { it.button == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        items.add(toIndex, items.removeAt(fromIndex))
        didDrag = true
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && didDrag) {
            didDrag = false
            viewModel.move(items.map { it.button })
        }
    }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier) {
        items(items.size, key = { items[it].button }) { index ->
            val row = items[index]
            ReorderableItem(reorderState, row.button) {
                ButtonRow(row, onToggle = { viewModel.toggle(row.button) })
            }
        }
    }
}

/** The settings row opening [ReaderBottomButtonsScreen], summarising the bar as the reader draws it. */
@Composable
fun readerBottomButtonsPreference(
    selection: Preference<Set<String>>,
    order: Preference<List<String>>,
    scope: ReaderBottomButton.Scope,
): SettingsPreference.PreferenceItem.TextPreference {
    val navigator = LocalNavigator.currentOrThrow
    val selected by selection.collectAsState()
    val arranged by order.collectAsState()
    val names = ReaderBottomButton.ordered(selected, arranged, scope).map { stringResource(it.stringRes) }
    return SettingsPreference.PreferenceItem.TextPreference(
        title = stringResource(MR.strings.pref_reader_bottom_buttons),
        subtitle = names.joinToString().ifEmpty { stringResource(MR.strings.none) },
        onClick = { navigator.push(ReaderBottomButtonsScreen(scope)) },
    )
}

@Composable
private fun ReorderableCollectionItemScope.ButtonRow(
    row: ReaderBottomButtonsViewModel.Row,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.enabled, onCheckedChange = { onToggle() })
        Text(
            text = stringResource(row.button.stringRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.small),
        )
        Icon(
            imageVector = MaterialSymbols.Rounded.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.draggableHandle(),
        )
    }
}
