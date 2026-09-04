package reikai.presentation.details

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
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

/**
 * Shared details toolbar for manga and novels: a Filter action tinted when a filter is active, the
 * Download menu, one overflow, and the action-mode select-all / invert / hide-or-unhide. Each per-type
 * capability is a nullable slot, so a content type lights up only what it supports and the two cannot
 * drift; a null callback hides its item. Title and background fade with scroll through the alpha
 * providers the details shell feeds.
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
    // Recommendations, non-null only when a manga's related suggestions are placed in this menu.
    onClickRecommendations: (() -> Unit)? = null,

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
                                icon = MaterialSymbols.Rounded.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = MaterialSymbols.Rounded.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        )
                        // Unhide only when every selected row is already hidden (reachable via the
                        // "Show hidden chapters" view); otherwise the action hides them. Same eye / eye-off
                        // icons as the category list (CategoryListItem).
                        if (onHide != null && onUnhide != null) {
                            if (allHiddenSelected) {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_unhide),
                                        icon = MaterialSymbols.Rounded.Visibility,
                                        onClick = onUnhide,
                                    ),
                                )
                            } else {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_hide),
                                        icon = MaterialSymbols.Rounded.VisibilityOff,
                                        onClick = onHide,
                                    ),
                                )
                            }
                        }
                        return@buildList
                    }
                    if (onClickDownload != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.manga_download),
                                icon = MaterialSymbols.Rounded.Download,
                                onClick = { downloadExpanded = !downloadExpanded },
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = MaterialSymbols.Rounded.FilterList,
                            iconTint = filterTint,
                            onClick = onClickFilter,
                        ),
                    )
                    // Overflow order (both types): Refresh, Edit categories, Edit info, Migrate, Manage
                    // sources, Notes, Share, Gallery info, Show/Hide hidden. Each is gated on its callback,
                    // so a content type shows only the items it supports.
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_webview_refresh),
                            onClick = onClickRefresh,
                        ),
                    )
                    if (onClickEditCategory != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit_categories),
                                onClick = onClickEditCategory,
                            ),
                        )
                    }
                    if (onClickEditInfo != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit_info),
                                onClick = onClickEditInfo,
                            ),
                        )
                    }
                    if (onClickMigrate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_migrate),
                                onClick = onClickMigrate,
                            ),
                        )
                    }
                    if (onClickManageSources != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_manage_sources),
                                onClick = onClickManageSources,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_notes),
                            onClick = onClickEditNotes,
                        ),
                    )
                    if (onClickShare != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = onClickShare,
                            ),
                        )
                    }
                    if (onClickMetadataViewer != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_metadata_viewer),
                                onClick = onClickMetadataViewer,
                            ),
                        )
                    }
                    if (onClickRecommendations != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.pref_recommendations),
                                onClick = onClickRecommendations,
                            ),
                        )
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
