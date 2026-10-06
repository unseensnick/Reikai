package reikai.presentation.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * Shared tablet (two-pane) shell for the manga and novel details screens: the Scaffold, resume/start
 * FAB, pull-to-refresh, and the [TwoPanelBox] with the info pane on the left and the chapter pane on
 * the right (with its fast-scroller). Mirrors [EntryDetailsScaffold] for the wide layout, and like it
 * takes every slot from EntryDetailsContent: [startContent] is the info pane, [endContent] the chapters.
 */
@Composable
fun EntryDetailsTwoPaneScaffold(
    chapterListState: LazyListState,
    snackbarHostState: SnackbarHostState,
    isAnySelected: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onCancelSelection: () -> Unit,
    fabVisible: Boolean,
    fabIsResume: Boolean,
    onFabClick: () -> Unit,
    topBar: @Composable (modifier: Modifier) -> Unit,
    bottomActionMenu: @Composable () -> Unit,
    startContent: LazyListScope.(appBarPadding: Dp) -> Unit,
    endContent: LazyListScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }

    BackHandler(enabled = isAnySelected) { onCancelSelection() }

    Scaffold(
        topBar = { topBar(Modifier.onSizeChanged { topBarHeight = it.height }) },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                bottomActionMenu()
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            EntryDetailsFab(
                chapterListState = chapterListState,
                visible = fabVisible,
                isResume = fabIsResume,
                onClick = onFabClick,
            )
        },
    ) { contentPadding ->
        PullRefresh(
            refreshing = isRefreshing,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = with(density) { topBarHeight.toDp() },
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            TwoPanelBox(
                modifier = Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
                startContent = {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    ) {
                        // The start pane only pads the bottom; the info box clears the app bar itself.
                        startContent(contentPadding.calculateTopPadding())
                    }
                },
                endContent = {
                    VerticalFastScroller(
                        listState = chapterListState,
                        topContentPadding = contentPadding.calculateTopPadding(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = chapterListState,
                            contentPadding = PaddingValues(
                                top = contentPadding.calculateTopPadding(),
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        ) {
                            endContent()
                        }
                    }
                },
            )
        }
    }
}
