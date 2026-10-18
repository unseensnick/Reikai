package tachiyomi.presentation.core.components.material

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import tachiyomi.presentation.core.components.Pill

@Composable
fun TabText(text: String, badgeCount: Int? = null) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f

    // RK --> the label gives way before the badge does. A fixed tab row splits its width evenly, so a
    // long label in a narrow tab pushed the count off its own edge and clipped it. A scrollable row
    // gives its tabs no width limit, so there the label keeps its own width (a weighted label measured
    // to zero there and left the library's category tabs showing only their counts).
    Layout(
        content = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badgeCount != null) {
                Pill(
                    text = "$badgeCount",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                    fontSize = 10.sp,
                )
            }
        },
    ) { measurables, constraints ->
        val badge = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0))
        val badgeWidth = badge?.width ?: 0
        val labelMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - badgeWidth).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val label = measurables[0].measure(constraints.copy(minWidth = 0, maxWidth = labelMaxWidth))
        val height = maxOf(label.height, badge?.height ?: 0)
        layout(label.width + badgeWidth, height) {
            label.placeRelative(0, (height - label.height) / 2)
            badge?.placeRelative(label.width, (height - badge.height) / 2)
        }
    }
    // RK <--
}
