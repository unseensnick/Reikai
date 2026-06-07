package reikai.presentation.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Floating category jump control for the single-list library (Y2): previous category / scroll to
 * top / next category. Auto-hide, drag-to-reposition, and long-press actions layer on later.
 */
@Composable
fun ReikaiCategoryHopper(
    onUpClick: () -> Unit,
    onCenterClick: () -> Unit,
    onDownClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUpClick) {
                Icon(imageVector = Icons.Filled.KeyboardArrowUp, contentDescription = null)
            }
            IconButton(onClick = onCenterClick) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = null)
            }
            IconButton(onClick = onDownClick) {
                Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
    }
}
