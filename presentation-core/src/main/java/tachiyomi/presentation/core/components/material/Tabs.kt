package tachiyomi.presentation.core.components.material

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import tachiyomi.presentation.core.components.Pill

@Composable
fun TabText(text: String, badgeCount: Int? = null) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // RK: the label gives way before the badge does. A fixed tab row splits its width evenly,
            // so a long label in a narrow tab pushed the count off its own edge and clipped it; the
            // label can be read from where you are, a count that is half a pill cannot.
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badgeCount != null) {
            Pill(
                text = "$badgeCount",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                fontSize = 10.sp,
            )
        }
    }
}
