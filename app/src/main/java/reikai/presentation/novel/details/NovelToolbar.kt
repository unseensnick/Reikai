package reikai.presentation.novel.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

/**
 * Novel details toolbar, the novel twin of `MangaToolbar`: a Filter action (tinted when a filter is
 * active) opening the chapter-settings dialog, an overflow with Refresh / Edit categories / Edit info /
 * Manage sources (merged only) / Share / Show hidden chapters (when any are hidden), and the action-mode
 * select-all / invert / Hide-or-Unhide. Title + background fade with scroll via the alpha providers,
 * matching the manga side.
 */
@Composable
fun NovelToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickEditCategory: () -> Unit,
    onClickEditInfo: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickManageSources: (() -> Unit)?,
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    showHidden: Boolean,
    hasHiddenChapters: Boolean,
    allHiddenSelected: Boolean,
    onHide: () -> Unit,
    onUnhide: () -> Unit,
    onToggleShowHidden: () -> Unit,
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val isActionMode = actionModeCounter > 0
    AppBar(
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(title, modifier = Modifier.alpha(titleAlphaProvider()))
            }
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isActionMode) 1f else backgroundAlphaProvider()),
        navigateUp = navigateUp,
        actions = {
            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                actions = buildList {
                    if (isActionMode) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        )
                        // Unhide only when every selected row is already hidden (reachable via the
                        // "Show hidden chapters" view); otherwise the action hides them. Uses the same
                        // eye / eye-off icons as the category list (CategoryListItem).
                        if (allHiddenSelected) {
                            add(AppBar.Action(title = "Unhide", icon = Icons.Outlined.Visibility, onClick = onUnhide))
                        } else {
                            add(AppBar.Action(title = "Hide", icon = Icons.Outlined.VisibilityOff, onClick = onHide))
                        }
                        return@buildList
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            iconTint = filterTint,
                            onClick = onClickFilter,
                        ),
                    )
                    add(AppBar.OverflowAction(title = stringResource(MR.strings.action_webview_refresh), onClick = onClickRefresh))
                    add(AppBar.OverflowAction(title = stringResource(MR.strings.action_edit_categories), onClick = onClickEditCategory))
                    add(AppBar.OverflowAction(title = stringResource(MR.strings.action_edit), onClick = onClickEditInfo))
                    if (onClickManageSources != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_manage_sources), onClick = onClickManageSources))
                    }
                    if (onClickShare != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_share), onClick = onClickShare))
                    }
                    if (hasHiddenChapters || showHidden) {
                        add(
                            AppBar.OverflowAction(
                                title = if (showHidden) "Hide hidden chapters" else "Show hidden chapters",
                                onClick = onToggleShowHidden,
                            ),
                        )
                    }
                },
            )
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )
}
