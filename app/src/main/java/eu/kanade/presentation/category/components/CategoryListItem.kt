package eu.kanade.presentation.category.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.contentTypeLabel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.RadioButtonUnchecked
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import reikai.domain.category.isHidden
import sh.calvin.reorderable.ReorderableCollectionItemScope
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReorderableCollectionItemScope.CategoryListItem(
    category: Category,
    // RK: multi-select state. In selection mode a tap toggles selection (instead of opening rename)
    // and the per-row edit/hide/delete icons give way to a selected-state check.
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    // RK: toggle this category's hidden flag bit
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier,
    // RK: when false the drag handle is hidden (auto-sorted, or in selection mode)
    showDragHandle: Boolean = true,
    // RK --> jump a card to either end, where dragging it across a long list is not practical. Offered
    // wherever the order is manual, including under a content-type chip where drag is off.
    showMoveActions: Boolean = false,
    onMoveToTop: () -> Unit = {},
    onMoveToBottom: () -> Unit = {},
    // RK <--
) {
    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // RK --> a long-press enters selection; in selection mode a tap toggles. The selected
                // row is tinted so multi-select reads at a glance.
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                // RK <--
                .padding(vertical = MaterialTheme.padding.small)
                .padding(
                    start = MaterialTheme.padding.small,
                    end = MaterialTheme.padding.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // RK -->
            if (showDragHandle) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.DragHandle,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(MaterialTheme.padding.medium)
                        .draggableHandle(),
                )
            } else {
                // A small left gap so the title isn't flush against the card edge.
                Spacer(modifier = Modifier.width(12.dp))
            }
            // RK <--
            // RK: name over a secondary line naming the libraries this category applies to, since one
            // list now holds all three kinds and the type is otherwise invisible.
            Column(
                // RK: dim a hidden category so its state reads at a glance
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (category.isHidden) 0.5f else 1f),
            ) {
                Text(text = category.name)
                Text(
                    text = stringResource(category.contentTypeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // RK --> selection mode shows a selected-state check; otherwise the per-row actions
            // (hide is a Reikai addition; rename + delete are Mihon's).
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) {
                        MaterialSymbols.RoundedFilled.CheckCircle
                    } else {
                        MaterialSymbols.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                )
            } else {
                IconButton(onClick = onToggleHidden) {
                    Icon(
                        imageVector = if (category.isHidden) {
                            MaterialSymbols.Rounded.Visibility
                        } else {
                            MaterialSymbols.Rounded.VisibilityOff
                        },
                        contentDescription = stringResource(
                            if (category.isHidden) MR.strings.action_show_category else MR.strings.action_hide_category,
                        ),
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Edit,
                        contentDescription = stringResource(MR.strings.action_rename_category),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                    )
                }
                if (showMoveActions) {
                    CategoryMoveMenu(onMoveToTop = onMoveToTop, onMoveToBottom = onMoveToBottom)
                }
            }
            // RK <--
        }
    }
}

// RK -->
@Composable
private fun CategoryMoveMenu(
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = MaterialSymbols.Rounded.MoreVert,
                contentDescription = stringResource(MR.strings.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_move_to_top)) },
                onClick = {
                    expanded = false
                    onMoveToTop()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_move_to_bottom)) },
                onClick = {
                    expanded = false
                    onMoveToBottom()
                },
            )
        }
    }
}
// RK <--
