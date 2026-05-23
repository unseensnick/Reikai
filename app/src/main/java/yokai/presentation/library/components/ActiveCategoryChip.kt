package yokai.presentation.library.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Small floating pill that surfaces the currently visible category name. Mirrors the legacy
 * `header_card` layer from `library_controller.xml`: small rounded card centered at the top of
 * the grid area, semi-elevated, single-line.
 *
 * Non-interactive in Phase 2; the legacy tap-to-show-categories sheet may land in a later phase.
 */
@Composable
fun ActiveCategoryChip(
    name: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
