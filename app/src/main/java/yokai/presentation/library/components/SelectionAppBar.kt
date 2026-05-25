package yokai.presentation.library.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * A single entry in the contextual app bar's overflow menu. Mirrors the menu items inflated
 * from `R.menu.library_selection` on the legacy path; each entry has a label, a click handler,
 * and an optional `enabled` flag so commits can light up actions conditionally (e.g., merge is
 * disabled below two selections; migrate hides when every selection is local-source).
 */
data class SelectionAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Contextual top bar shown while at least one library item is selected. Mirrors the legacy
 * `ActionMode` started by `LibraryController.createActionModeIfNeeded` at
 * `LibraryController.kt:2000-2008`: a back/close affordance, "Selected: N" title, and a
 * MoreVert-driven dropdown that holds the menu items inflated from `R.menu.library_selection`.
 *
 * Per-commit growth: actions are passed in via [overflowActions] so each new action in C3-C7
 * just appends to the caller's list; the bar itself stays a stable shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionAppBar(
    selectionCount: Int,
    onClose: () -> Unit,
    colors: TopAppBarColors,
    overflowActions: List<SelectionAction> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(text = stringResource(MR.strings.selected_).format(selectionCount))
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.close),
                )
            }
        },
        actions = {
            actions()
            if (overflowActions.isNotEmpty()) {
                SelectionOverflowMenu(actions = overflowActions)
            }
        },
        colors = colors,
    )
}

@Composable
private fun SelectionOverflowMenu(actions: List<SelectionAction>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(MR.strings.more),
        )
    }
    DropdownMenu(
        expanded = open,
        onDismissRequest = { open = false },
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                enabled = action.enabled,
                onClick = {
                    open = false
                    action.onClick()
                },
            )
        }
    }
}
