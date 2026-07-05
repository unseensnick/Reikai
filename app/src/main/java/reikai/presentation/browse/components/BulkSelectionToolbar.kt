package reikai.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Top bar shown while bulk-selecting manga in a browse surface: the count, select-all / invert, and
 * an "add to library" action. Cancelling clears the selection.
 */
@Composable
fun BulkSelectionToolbar(
    selectedCount: Int,
    onClickClearSelection: () -> Unit,
    onChangeCategoryClick: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onReverseSelection: (() -> Unit)? = null,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            AppBarActions(
                actions = buildList {
                    if (onSelectAll != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Filled.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                    }
                    if (onReverseSelection != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onReverseSelection,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.add_to_library),
                            icon = Icons.Filled.Favorite,
                            onClick = { if (selectedCount > 0) onChangeCategoryClick() },
                        ),
                    )
                },
            )
        },
        isActionMode = true,
        onCancelActionMode = onClickClearSelection,
    )
}
