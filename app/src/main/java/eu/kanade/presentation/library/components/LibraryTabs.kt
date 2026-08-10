package eu.kanade.presentation.library.components

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import reikai.presentation.library.LibraryBucket // RK
import reikai.presentation.library.visualLabel // RK
import tachiyomi.presentation.core.components.material.TabText

@Composable
internal fun LibraryTabs(
    // RK: a tab is one section of the assembled library, which is a category or a dynamic group
    buckets: List<LibraryBucket>,
    pagerState: PagerState,
    getItemCountForCategory: (LibraryBucket) -> Int?,
    onTabItemClick: (Int) -> Unit,
) {
    val currentPageIndex = pagerState.currentPage.coerceAtMost(buckets.lastIndex)
    PrimaryScrollableTabRow(
        selectedTabIndex = currentPageIndex,
        edgePadding = 0.dp,
    ) {
        buckets.forEachIndexed { index, bucket ->
            Tab(
                selected = currentPageIndex == index,
                onClick = { onTabItemClick(index) },
                text = {
                    TabText(
                        text = bucket.visualLabel, // RK
                        badgeCount = getItemCountForCategory(bucket),
                    )
                },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
