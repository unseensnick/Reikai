package reikai.presentation.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

/**
 * Shared details toolbar for manga and novels: a Filter action (tinted when a filter is active), the
 * Download menu, one overflow, and the action-mode select-all / invert / hide-or-unhide. Each per-type
 * capability is a nullable slot, so a content type lights up only what it supports and the two toolbars
 * cannot drift. Title + background fade with scroll via the alpha providers, which the details shell feeds.
 *
 * Type-specific slots: [onClickMetadataViewer] (manga gallery sources only), [onClickEditInfo] and the
 * hide/unhide cluster ([onHide] / [onUnhide] / [onToggleShowHidden]) are the novel additions; a null
 * callback hides its item, so manga can leave them off until it wires them in a later phase.
 */
@Composable
fun EntryToolbar(
    title: String,
    hasFilters: Boolean,
    navigateUp: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickEditCategory: (() -> Unit)?,
    onClickEditNotes: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickManageSources: (() -> Unit)?,
    onClickMigrate: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    // Edit metadata. Non-null for novels; manga wires it once the shared editor lands.
    onClickEditInfo: (() -> Unit)? = null,
    // Gallery metadata viewer, non-null only for adult/metadata manga sources.
    onClickMetadataViewer: (() -> Unit)? = null,

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    // Hide/unhide chapters. Non-null for novels; manga wires them once it gains the mechanism.
    showHidden: Boolean = false,
    hasHiddenChapters: Boolean = false,
    allHiddenSelected: Boolean = false,
    onHide: (() -> Unit)? = null,
    onUnhide: (() -> Unit)? = null,
    onToggleShowHidden: (() -> Unit)? = null,

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
            // Declared inside the actions RowScope so the menu anchors under the download icon.
            var downloadExpanded by remember { mutableStateOf(false) }
            if (onClickDownload != null) {
                DownloadDropdownMenu(
                    expanded = downloadExpanded,
                    onDismissRequest = { downloadExpanded = false },
                    onDownloadClicked = onClickDownload,
                )
            }
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
                        // "Show hidden chapters" view); otherwise the action hides them. Same eye / eye-off
                        // icons as the category list (CategoryListItem).
                        if (onHide != null && onUnhide != null) {
                            if (allHiddenSelected) {
                                add(AppBar.Action(title = stringResource(MR.strings.action_unhide), icon = Icons.Outlined.Visibility, onClick = onUnhide))
                            } else {
                                add(AppBar.Action(title = stringResource(MR.strings.action_hide), icon = Icons.Outlined.VisibilityOff, onClick = onHide))
                            }
                        }
                        return@buildList
                    }
                    if (onClickDownload != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.manga_download),
                                icon = Icons.Outlined.Download,
                                onClick = { downloadExpanded = !downloadExpanded },
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            iconTint = filterTint,
                            onClick = onClickFilter,
                        ),
                    )
                    // Overflow order (both types): Refresh, Edit categories, Edit info, Migrate, Manage
                    // sources, Notes, Share, Gallery info, Show/Hide hidden. Each is gated on its callback,
                    // so a content type shows only the items it supports.
                    add(AppBar.OverflowAction(title = stringResource(MR.strings.action_webview_refresh), onClick = onClickRefresh))
                    if (onClickEditCategory != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_edit_categories), onClick = onClickEditCategory))
                    }
                    if (onClickEditInfo != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_edit), onClick = onClickEditInfo))
                    }
                    if (onClickMigrate != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_migrate), onClick = onClickMigrate))
                    }
                    if (onClickManageSources != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_manage_sources), onClick = onClickManageSources))
                    }
                    add(AppBar.OverflowAction(title = stringResource(MR.strings.action_notes), onClick = onClickEditNotes))
                    if (onClickShare != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_share), onClick = onClickShare))
                    }
                    if (onClickMetadataViewer != null) {
                        add(AppBar.OverflowAction(title = stringResource(MR.strings.action_metadata_viewer), onClick = onClickMetadataViewer))
                    }
                    if (onToggleShowHidden != null && (hasHiddenChapters || showHidden)) {
                        add(
                            AppBar.OverflowAction(
                                title = if (showHidden) {
                                    stringResource(MR.strings.action_hide_hidden_chapters)
                                } else {
                                    stringResource(MR.strings.action_show_hidden_chapters)
                                },
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
