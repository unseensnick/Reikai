package reikai.presentation.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold

/**
 * Shared phone shell for the manga and novel details screens: the Scaffold, the resume/start FAB, the
 * pull-to-refresh, the scroll-driven toolbar fade, and the chapter LazyColumn. Both content types drive
 * it from neutral scalars. EntryDetailsContent fills the slots once for both: [topBar] (the shared
 * toolbar, fed the shell's fade providers), [bottomActionMenu] (the selection bar) and [content] (the
 * LazyColumn body). The tablet path is EntryDetailsTwoPaneScaffold.
 */
@Composable
fun EntryDetailsScaffold(
    listState: LazyListState,
    snackbarHostState: SnackbarHostState,
    isAnySelected: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onCancelSelection: () -> Unit,
    fabVisible: Boolean,
    fabIsResume: Boolean,
    onFabClick: () -> Unit,
    topBar: @Composable (titleAlpha: () -> Float, backgroundAlpha: () -> Float) -> Unit,
    bottomActionMenu: @Composable () -> Unit,
    content: LazyListScope.(appBarPadding: Dp) -> Unit,
) {
    BackHandler(enabled = isAnySelected) { onCancelSelection() }

    Scaffold(
        topBar = {
            val isFirstItemVisible by remember {
                derivedStateOf { listState.firstVisibleItemIndex == 0 }
            }
            val isFirstItemScrolled by remember {
                derivedStateOf { listState.firstVisibleItemScrollOffset > 0 }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "Top Bar Title",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "Top Bar Background",
            )
            topBar({ titleAlpha }, { backgroundAlpha })
        },
        bottomBar = bottomActionMenu,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            EntryDetailsFab(
                chapterListState = listState,
                visible = fabVisible,
                isResume = fabIsResume,
                onClick = onFabClick,
            )
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        PullRefresh(
            refreshing = isRefreshing,
            onRefresh = onRefresh,
            enabled = !isAnySelected,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            val layoutDirection = LocalLayoutDirection.current
            val lazyColumn = @Composable {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                    content(topPadding)
                }
            }
            VerticalFastScroller(
                listState = listState,
                topContentPadding = topPadding,
                endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
            ) {
                lazyColumn()
            }
        }
    }
}
