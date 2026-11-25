package reikai.presentation.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Label
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Floating category jump control for the single-list library: previous category / jump to a category
 * / next category. The center button also fires a configurable [onCenterLongClick] on long press
 * (hopper long-press action).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReikaiCategoryHopper(
    onUpClick: () -> Unit,
    onCenterClick: () -> Unit,
    onCenterLongClick: () -> Unit,
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
                Icon(
                    imageVector = MaterialSymbols.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(MR.strings.previous_category),
                )
            }
            // Raw combinedClickable (not IconButton) since IconButton has no long-press slot.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = onCenterClick, onLongClick = onCenterLongClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.AutoMirroredRounded.Label,
                    contentDescription = stringResource(MR.strings.jump_to_category),
                )
            }
            IconButton(onClick = onDownClick) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(MR.strings.next_category),
                )
            }
        }
    }
}
