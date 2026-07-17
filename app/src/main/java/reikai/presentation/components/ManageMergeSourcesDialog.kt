package reikai.presentation.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

/** One source row in [ManageMergeSourcesDialog]; content-neutral so manga and novels share the dialog.
 *  [subtitle] carries the novel chapter-count coverage hint and is null for manga. */
data class ManageMergeSourceRow(
    val id: Long,
    val sourceName: String,
    val subtitle: String? = null,
)

/**
 * Lists the sources merged into one series (manga or novel) and lets the user reorder which one leads
 * the combined chapter list, split a source back out, split-and-remove it, or dissolve the whole group.
 * The single shared dialog for both content types (rows are keyed on the member entry id, a Long for
 * both), replacing the former per-type twins.
 *
 * Reordering applies live: a drag persists immediately through [onReorder] and turns on the group's
 * source-ranking override, so the top row becomes the primary source. [onResetOrder] clears the
 * override back to the global ranking (offered only while one is set). Single-row actions use the
 * per-row split / remove controls; long-press enters a contextual multi-select for batch split / remove.
 *
 * Every structural action dismisses the dialog, so the member list is stable while open: the reorder
 * list is seeded once and never needs to reconcile an external change.
 */
@Composable
fun ManageMergeSourcesDialog(
    sources: List<ManageMergeSourceRow>,
    isOverridden: Boolean,
    onDismissRequest: () -> Unit,
    onReorder: (orderedIds: List<Long>) -> Unit,
    onResetOrder: () -> Unit,
    onSplit: (List<Long>) -> Unit,
    onRemoveFromLibrary: (List<Long>) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val items = remember { sources.toMutableStateList() }
    val listState = rememberLazyListState()
    var didDrag by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<Long>() }
    var selectionAnchor by remember { mutableStateOf<Long?>(null) }
    // A drag turns the override on host-side; track it locally so the primary badge and Reset action
    // appear immediately, without waiting for the persisted flag to round-trip back.
    var overridden by remember { mutableStateOf(isOverridden) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = items.indexOfFirst { it.id == from.key }
        val toIndex = items.indexOfFirst { it.id == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        items.add(toIndex, items.removeAt(fromIndex))
        didDrag = true
    }

    // Persist the new order once the drag settles (turning the override on), not on every frame.
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && didDrag) {
            didDrag = false
            overridden = true
            onReorder(items.map { it.id })
        }
    }

    fun exitSelection() {
        selectionMode = false
        selection.clear()
        selectionAnchor = null
    }

    fun toggle(id: Long) {
        if (!selection.remove(id)) selection.add(id)
        selectionAnchor = id.takeIf { selection.isNotEmpty() }
        if (selection.isEmpty()) selectionMode = false
    }

    /** Long-press range fill, matching the library: from the last-touched anchor to [id] inclusive, over
     *  the current display order. The first long-press (no anchor yet) just selects that one row. */
    fun rangeSelect(id: Long) {
        selectionMode = true
        val ids = items.map { it.id }
        val anchorIndex = selectionAnchor?.let(ids::indexOf) ?: -1
        val targetIndex = ids.indexOf(id)
        if (anchorIndex < 0 || targetIndex < 0) {
            if (id !in selection) selection.add(id)
        } else {
            val range = if (anchorIndex <= targetIndex) anchorIndex..targetIndex else targetIndex..anchorIndex
            range.forEach { ids[it].takeIf { candidate -> candidate !in selection }?.let(selection::add) }
        }
        selectionAnchor = id
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(MR.strings.action_manage_sources),
                    modifier = Modifier.weight(1f),
                )
                // A visible entry point to multi-select, since long-press alone isn't discoverable.
                IconButton(
                    onClick = { if (selectionMode) exitSelection() else selectionMode = true },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Checklist,
                        contentDescription = stringResource(MR.strings.action_bulk_select),
                        tint = if (selectionMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The order is authoritative only under an override, so guide the user toward setting one.
                if (!overridden && !selectionMode) {
                    Text(
                        text = stringResource(MR.strings.merge_sources_reorder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    state = listState,
                    // Bounded so the lazy list has a finite height inside the dialog (an unbounded max
                    // would crash); short groups never reach it.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(items, key = { it.id }) { row ->
                        ReorderableItem(reorderState, key = row.id) {
                            SourceRow(
                                row = row,
                                // Rows arrive trunk-first and a drag moves the new trunk to the top, so
                                // the primary is always the first row.
                                isPrimary = items.firstOrNull()?.id == row.id,
                                isSelected = row.id in selection,
                                selectionMode = selectionMode,
                                onClick = { if (selectionMode) toggle(row.id) },
                                onLongClick = { rangeSelect(row.id) },
                                onSplit = { onSplit(listOf(row.id)) },
                                onRemove = { onRemoveFromLibrary(listOf(row.id)) },
                            )
                        }
                    }
                }
            }
        },
        // One full-width row so the dismiss button sits in the bottom-left corner and the actions stay
        // at the right, instead of both clustering at the end like the default dialog button layout.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (selectionMode) exitSelection() else onDismissRequest() }) {
                    Text(
                        stringResource(
                            if (selectionMode) MR.strings.action_cancel else MR.strings.action_close,
                        ),
                    )
                }
                Row {
                    if (selectionMode) {
                        TextButton(onClick = { onSplit(selection.toList()) }) {
                            Text(stringResource(MR.strings.merge_sources_split_action))
                        }
                        TextButton(onClick = { onRemoveFromLibrary(selection.toList()) }) {
                            Text(stringResource(MR.strings.merge_sources_remove_action))
                        }
                    } else {
                        if (overridden) {
                            TextButton(onClick = onResetOrder) {
                                Text(stringResource(MR.strings.merge_sources_reset_order_action))
                            }
                        }
                        TextButton(onClick = onRemoveAll) {
                            Text(stringResource(MR.strings.merge_sources_remove_all_action))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ReorderableCollectionItemScope.SourceRow(
    row: ManageMergeSourceRow,
    isPrimary: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSplit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Match the per-row icon buttons' 48dp touch target, so the rows keep their height in
            // selection mode where those buttons (and the height they set) are gone.
            .heightIn(min = 48.dp)
            .selectedBackground(isSelected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .draggableHandle(),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.sourceName,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isPrimary) {
                    Text(
                        text = stringResource(MR.strings.merge_sources_primary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            row.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Per-row actions collapse while multi-selecting; the batch actions take over in the button row.
        if (!selectionMode) {
            IconButton(onClick = onSplit) {
                Icon(
                    imageVector = Icons.Outlined.CallSplit,
                    contentDescription = stringResource(MR.strings.merge_sources_split_action),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.merge_sources_remove_action),
                )
            }
        }
    }
}
