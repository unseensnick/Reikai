package reikai.presentation.browse.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceEHentaiList
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.network.interceptor.cloudflareBlockedUrl
import mihon.app.di.appGraph
import reikai.presentation.browse.EntryBrowseGridCell
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

/**
 * The one catalogue body, for a manga source and a light-novel source alike: the loading, empty and
 * fetch-error states, then the results in whichever layout [rowStyle] asks for. Both catalogues used
 * to carry their own copy of all of it, which is how novels ended up ignoring the column preference.
 *
 * [onLocalSourceHelpClick] replaces the whole empty-state action set when present, the way upstream
 * points a reader at the local-source guide instead of offering a retry there is nothing to retry.
 */
@Composable
fun EntryBrowseCatalogue(
    rows: LazyPagingItems<EntryBrowseRow>,
    rowStyle: EntryBrowseRowStyle,
    selectedKeys: Set<String>,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: (challengeUrl: String?) -> Unit,
    onHelpClick: () -> Unit,
    onClick: (EntryBrowseRow) -> Unit,
    onLongClick: (EntryBrowseRow) -> Unit,
    onLocalSourceHelpClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    val errorState = rows.loadState.refresh.takeIf { it is LoadState.Error }
        ?: rows.loadState.append.takeIf { it is LoadState.Error }

    // A Cloudflare challenge blocks one URL, and a source root is often not challenged at all, so
    // opening that leaves the reader nothing to solve and every retry keeps failing.
    val challengeUrl = (errorState as? LoadState.Error)?.error?.cloudflareBlockedUrl()

    // Indefinite: a fetch error stays until it is dismissed or retried. Raised only once results are
    // already on screen; an empty listing routes the same error through the empty state below.
    LaunchedEffect(errorState) {
        if (rows.itemCount > 0 && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = with(context) { errorState.error.formattedMessage },
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> rows.retry()
            }
        }
    }

    if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (rows.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = (errorState as? LoadState.Error)
                ?.let { with(context) { it.error.formattedMessage } }
                ?: stringResource(MR.strings.no_results_found),
            actions = onLocalSourceHelpClick?.let {
                listOf(EmptyScreenAction(MR.strings.local_source_help_guide, HelpIcon, it))
            } ?: listOf(
                EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, rows::refresh),
                EmptyScreenAction(MR.strings.action_open_in_web_view, Icons.Outlined.Public) {
                    onWebViewClick(challengeUrl)
                },
                EmptyScreenAction(MR.strings.label_help, HelpIcon, onHelpClick),
            ),
        )
        return
    }

    when (rowStyle) {
        // The adult-source layout brings its own rows and reads the gallery metadata off the payload,
        // which only the manga adapter puts there. Reached only when that adapter asks for it.
        EntryBrowseRowStyle.Gallery -> BrowseSourceEHentaiList(
            rows = rows,
            contentPadding = contentPadding,
            selectedKeys = selectedKeys,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        is EntryBrowseRowStyle.Standard -> StandardRows(
            rows = rows,
            displayMode = rowStyle.displayMode,
            selectedKeys = selectedKeys,
            contentPadding = contentPadding,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun StandardRows(
    rows: LazyPagingItems<EntryBrowseRow>,
    displayMode: LibraryDisplayMode,
    selectedKeys: Set<String>,
    contentPadding: PaddingValues,
    onClick: (EntryBrowseRow) -> Unit,
    onLongClick: (EntryBrowseRow) -> Unit,
) {
    val cell: @Composable (Int) -> Unit = { index ->
        val row = rows[index]
        if (row != null) {
            val content by row.content.collectAsState()
            val haptic = LocalHapticFeedback.current
            EntryBrowseGridCell(
                ui = content.ui,
                displayMode = displayMode,
                onClick = { onClick(row) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick(row)
                },
                isSelected = row.key in selectedKeys,
            )
        }
    }

    if (displayMode == LibraryDisplayMode.List) {
        LazyColumn(contentPadding = contentPadding + PaddingValues(vertical = 8.dp)) {
            if (rows.loadState.prepend is LoadState.Loading) item { BrowseSourceLoadingItem() }
            items(count = rows.itemCount) { cell(it) }
            if (rows.isAppending) item { BrowseSourceLoadingItem() }
        }
        return
    }

    LazyVerticalGrid(
        columns = rememberBrowseColumns(),
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (rows.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadingItem() }
        }
        items(count = rows.itemCount) { cell(it) }
        if (rows.isAppending) {
            item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadingItem() }
        }
    }
}

/**
 * The library's own column count, which both catalogues now follow: one screen serving two content
 * types has one grid, so a per-type column setting would be two answers to one question. Zero means
 * the adaptive width upstream uses.
 */
@Composable
private fun rememberBrowseColumns(): GridCells {
    val context = LocalContext.current
    val preferences = remember { context.appGraph.libraryPreferences }
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val columns = remember(isLandscape) {
        (if (isLandscape) preferences.landscapeColumns else preferences.portraitColumns).get()
    }
    return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
}

private val LazyPagingItems<*>.isAppending: Boolean
    get() = loadState.refresh is LoadState.Loading || loadState.append is LoadState.Loading

private val HelpIcon = Icons.AutoMirrored.Outlined.HelpOutline
