package eu.kanade.presentation.category.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
                    imageVector = Icons.Outlined.DragHandle,
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
            Text(
                text = category.name,
                // RK: dim a hidden category's name so its state reads at a glance
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (category.isHidden) 0.5f else 1f),
            )
            // RK --> selection mode shows a selected-state check; otherwise the per-row actions
            // (hide is a Reikai addition; rename + delete are Mihon's).
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
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
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        contentDescription = stringResource(
                            if (category.isHidden) MR.strings.action_show_category else MR.strings.action_hide_category,
                        ),
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(MR.strings.action_rename_category),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                    )
                }
            }
            // RK <--
        }
    }
}
