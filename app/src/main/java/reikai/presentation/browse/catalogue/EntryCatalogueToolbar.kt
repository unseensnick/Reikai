package reikai.presentation.browse.catalogue

import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ViewList
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.icons.materialsymbols.rounded.ViewModule
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EntryCatalogueToolbar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    /** Null hides the display-mode menu: the layout in play has no modes to choose between. */
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    /** The source has a page of its own; false offers Help in its place, as a local source needs. */
    hasWebView: Boolean,
    hasSettings: Boolean,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    /** Leaving the search bar: clears the search rather than the source. The X beside the field is
     *  upstream's own reset, which only empties the text. */
    onCloseSearch: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onToggleSelectionMode: (() -> Unit)? = null,
    /** Null when the listing on screen holds nothing worth saving, which hides the action. */
    onSaveSearchClick: (() -> Unit)? = null,
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = onCloseSearch,
        actions = {
            AppBarActions(
                actions = buildList {
                    if (displayMode != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_display_mode),
                                icon = if (displayMode == LibraryDisplayMode.List) {
                                    MaterialSymbols.AutoMirroredRounded.ViewList
                                } else {
                                    MaterialSymbols.Rounded.ViewModule
                                },
                                onClick = { selectingDisplayMode = true },
                            ),
                        )
                    }
                    // Bulk-select entry
                    if (onToggleSelectionMode != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_bulk_select),
                                icon = MaterialSymbols.Rounded.SelectAll,
                                onClick = onToggleSelectionMode,
                            ),
                        )
                    }
                    if (onSaveSearchClick != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_save_search),
                                onClick = onSaveSearchClick,
                            ),
                        )
                    }
                    if (hasWebView) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = onWebViewClick,
                            ),
                        )
                    } else {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.label_help),
                                onClick = onHelpClick,
                            ),
                        )
                    }
                    if (hasSettings) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_settings),
                                onClick = onSettingsClick,
                            ),
                        )
                    }
                },
            )

            DropdownMenu(
                expanded = selectingDisplayMode && displayMode != null,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
