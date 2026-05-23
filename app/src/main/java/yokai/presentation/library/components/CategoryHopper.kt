package yokai.presentation.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * Three-button hopper card for category navigation, mirroring the legacy
 * `rounded_category_hopper`: up + category picker + down. `colorSecondary` background reads as
 * an action surface against the grid. Drag-to-reposition lives in the caller (LibraryContent
 * wraps this with a `Modifier.draggable` so we can keep the component pure).
 */
@Composable
fun CategoryHopper(
    onUpClick: () -> Unit,
    onCenterClick: () -> Unit,
    onDownClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(
                onClick = onUpClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ExpandLess,
                    contentDescription = stringResource(MR.strings.previous_title),
                )
            }
            IconButton(
                onClick = onCenterClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Label,
                    contentDescription = stringResource(MR.strings.categories),
                )
            }
            IconButton(
                onClick = onDownClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(MR.strings.next_title),
                )
            }
        }
    }
}
