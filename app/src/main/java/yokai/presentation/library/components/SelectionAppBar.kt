package yokai.presentation.library.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * Contextual top bar shown while at least one library item is selected. Mirrors the legacy
 * `ActionMode` started by `LibraryController.createActionModeIfNeeded` at
 * `LibraryController.kt:2000-2008`: a back/close affordance, "Selected: N" title, and an
 * actions slot that holds the menu items inflated from `R.menu.library_selection`.
 *
 * Caller hands in the [actions] slot per commit as Phase 5 fills out the eight library
 * actions, plus the new unmerge action. C2 wires the shell with an empty actions slot; later
 * commits hang menu items off it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionAppBar(
    selectionCount: Int,
    onClose: () -> Unit,
    colors: TopAppBarColors,
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
        actions = actions,
        colors = colors,
    )
}
