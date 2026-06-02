package yokai.presentation.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * A single entry in the contextual app bar's actions surface. Mirrors the menu items inflated
 * from `R.menu.library_selection` on the legacy path. When [icon] is non-null the entry
 * renders as an `IconButton` in the bar (legacy `showAsAction="ifRoom"` equivalent); when null
 * it lands in the MoreVert overflow dropdown (legacy `showAsAction="never"` equivalent).
 *
 * [enabled] dims the entry without removing it; callers omit entries that should be hidden
 * entirely (e.g., migrate when every selection is local-source).
 */
data class SelectionAction(
    val label: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * Contextual top bar shown while at least one library item is selected. Mirrors the legacy
 * `ActionMode` started by `LibraryController.createActionModeIfNeeded` at
 * `LibraryController.kt:2000-2008`: a back/close affordance, "Selected: N" title, primary
 * actions rendered as IconButtons (ifRoom equivalent), and a MoreVert dropdown for the rest.
 *
 * Splits [actions] on the presence of [SelectionAction.icon]: icon-carrying entries become
 * bar IconButtons in order; the rest fall into the overflow dropdown in their original order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionAppBar(
    selectionCount: Int,
    onClose: () -> Unit,
    colors: TopAppBarColors,
    actions: List<SelectionAction> = emptyList(),
) {
    val barActions = actions.filter { it.icon != null }
    val overflow = actions.filter { it.icon == null }
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
            barActions.forEach { action ->
                IconButton(onClick = action.onClick, enabled = action.enabled) {
                    Icon(
                        imageVector = action.icon!!,
                        contentDescription = action.label,
                    )
                }
            }
            if (overflow.isNotEmpty()) {
                SelectionOverflowMenu(actions = overflow)
            }
        },
        colors = colors,
    )
}

@Composable
private fun SelectionOverflowMenu(actions: List<SelectionAction>) {
    var open by remember { mutableStateOf(false) }
    // Box-wrapped so the dropdown anchors to the icon; a bare DropdownMenu placed directly in the
    // TopAppBar actions RowScope anchors to the row instead and opens mis-positioned. Matches the
    // non-selection details overflow.
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(MR.strings.more),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
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
}
