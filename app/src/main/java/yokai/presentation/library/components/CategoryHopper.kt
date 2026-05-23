package yokai.presentation.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
 * Up / down hopper card for category navigation. Mirrors the legacy `rounded_category_hopper`
 * (minus the center category-picker button, which can land in a follow-up commit). Two
 * IconButtons in a horizontally-aligned rounded card; the card uses `colorSecondary` /
 * `onSecondary` to read as an action surface against the grid background.
 */
@Composable
fun CategoryHopper(
    onUpClick: () -> Unit,
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
