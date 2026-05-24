package yokai.presentation.library.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet

/**
 * Reorder UI for the Filter tab. Each row shows the filter's label plus up / down arrows that
 * swap it with the adjacent row. Simpler than a drag-handle gesture for a first ship; the
 * legacy `ExpandedFilterSheet` also uses tap-to-swap, so this matches the established UX.
 *
 * The caller owns the [order] string and persists it to `preferences.filterOrder()` via
 * [onOrderChanged] each time the user nudges a row. The order is the same single-char string
 * the legacy uses (default "urdcmbts"), so values written here are read back by both paths.
 */
@Composable
fun FilterReorderList(
    order: String,
    onOrderChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chars = order.toCharArray().toMutableList()
    Column(modifier = modifier.fillMaxWidth()) {
        chars.forEachIndexed { index, c ->
            val filter = FilterBottomSheet.Filters.filterOf(c) ?: return@forEachIndexed
            FilterReorderRow(
                label = stringResource(filter.stringRes),
                canMoveUp = index > 0,
                canMoveDown = index < chars.lastIndex,
                onMoveUp = {
                    val swapped = chars.toMutableList()
                    val tmp = swapped[index - 1]
                    swapped[index - 1] = swapped[index]
                    swapped[index] = tmp
                    onOrderChanged(String(swapped.toCharArray()))
                },
                onMoveDown = {
                    val swapped = chars.toMutableList()
                    val tmp = swapped[index + 1]
                    swapped[index + 1] = swapped[index]
                    swapped[index] = tmp
                    onOrderChanged(String(swapped.toCharArray()))
                },
            )
        }
    }
}

@Composable
private fun FilterReorderRow(
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
    }
}
