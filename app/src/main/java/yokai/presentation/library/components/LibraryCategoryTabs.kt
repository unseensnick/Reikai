package yokai.presentation.library.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.database.models.ILibraryCategory

/**
 * Komikku-style horizontally scrollable category tab row, shown above the library grid when
 * "show all categories" is off so the user can switch categories (and see ones that scroll
 * off-screen). Tapping a tab dispatches through the same active-category path the hopper and
 * swipe gesture use, so all three stay in sync. Transparent container + thin primary indicator
 * to blend with the top bar.
 */
@Composable
fun LibraryCategoryTabs(
    categories: List<ILibraryCategory>,
    selectedId: Int?,
    onSelect: (ILibraryCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.size <= 1) return
    val selectedIndex = categories.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 12.dp,
        containerColor = Color.Transparent,
        indicator = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        divider = {},
    ) {
        categories.forEachIndexed { index, category ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(category) },
                text = {
                    Text(
                        text = category.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}
