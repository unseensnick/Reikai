package reikai.presentation.novel.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
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
 * active) opening the chapter-settings dialog, an overflow with Refresh / Edit categories / Edit info
 * / Share, and the action-mode select-all / invert. Title + background fade with scroll via the alpha
 * providers, matching the manga side. Download (S5), migrate (later), and manage-sources (S8) are
 * intentionally absent.
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
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
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
                    if (onClickShare != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_share), onClick = onClickShare))
                    }
                },
            )
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )
}
