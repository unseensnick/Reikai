package yokai.presentation.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Single-select chip row used by the library filter tab. The first chip is always "All"
 * representing the IGNORE state (preference value 0); subsequent chips carry concrete
 * preference values (1 / 2 for include / exclude, or `Manga.TYPE_*` for the series-type filter).
 *
 * Chips are pill-shaped Surfaces (not Material3 `FilterChip`) so they render consistently in
 * both selected (filled secondary container) and unselected (subtle outline) states without
 * the FilterChip's auto-leading checkmark and heavier outline that looked out of place on the
 * sheet's dark surface. Chips wrap to multiple lines via [FlowRow].
 */
@Immutable
data class FilterChipOption(
    val value: Int,
    val label: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChipRow(
    label: String,
    options: List<FilterChipOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                LibraryPillChip(
                    text = option.label,
                    selected = option.value == selected,
                    onClick = { onSelect(option.value) },
                )
            }
        }
    }
}

@Composable
private fun LibraryPillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (selected) {
        null
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }

    Surface(
        onClick = onClick,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        )
    }
}
