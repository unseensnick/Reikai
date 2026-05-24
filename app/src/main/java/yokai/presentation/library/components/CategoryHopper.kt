package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * Three-button hopper card for category navigation, mirroring the legacy
 * `rounded_category_hopper`: up + category picker + down. `colorSecondary` background reads as
 * an action surface against the grid. Drag-to-reposition lives in the caller (LibraryContent
 * wraps this with a `Modifier.draggable` so we can keep the component pure).
 *
 * Each button accepts an optional long-click handler so the caller can hook the legacy
 * gestures: long-press up = scroll-to-top, long-press down = scroll-to-bottom, long-press
 * center = the configured `hopperLongPressAction`.
 */
@Composable
fun CategoryHopper(
    onUpClick: () -> Unit,
    onCenterClick: () -> Unit,
    onDownClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUpLongClick: (() -> Unit)? = null,
    onCenterLongClick: (() -> Unit)? = null,
    onDownLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            HopperButton(
                icon = Icons.Outlined.ExpandLess,
                contentDescription = stringResource(MR.strings.previous_title),
                onClick = onUpClick,
                onLongClick = onUpLongClick,
            )
            HopperButton(
                icon = Icons.Outlined.Label,
                contentDescription = stringResource(MR.strings.categories),
                onClick = onCenterClick,
                onLongClick = onCenterLongClick,
            )
            HopperButton(
                icon = Icons.Outlined.ExpandMore,
                contentDescription = stringResource(MR.strings.next_title),
                onClick = onDownClick,
                onLongClick = onDownLongClick,
            )
        }
    }
}

@Composable
private fun HopperButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    // Material3 IconButton doesn't expose onLongClick, so roll a Box + combinedClickable.
    // Size mirrors the IconButtonDefaults touch target so the hopper card stays the same width.
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
    }
}
