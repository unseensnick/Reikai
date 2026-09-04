package reikai.presentation.browse.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Top bar shown while bulk-selecting manga in a browse surface: the count, select-all / invert, and
 * an "add to library" action. Cancelling clears the selection.
 */
@Composable
fun BulkSelectionToolbar(
    selectedCount: Int,
    // Overrides the plain count, so a mixed selection can say how much of each kind it holds.
    title: String? = null,
    onClickClearSelection: () -> Unit,
    onChangeCategoryClick: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onReverseSelection: (() -> Unit)? = null,
) {
    AppBar(
        titleContent = { Text(text = title ?: "$selectedCount") },
        actions = {
            AppBarActions(
                actions = buildList {
                    if (onSelectAll != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = MaterialSymbols.Rounded.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                    }
                    if (onReverseSelection != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = MaterialSymbols.Rounded.FlipToBack,
                                onClick = onReverseSelection,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.add_to_library),
                            icon = MaterialSymbols.Rounded.Favorite,
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
