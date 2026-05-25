package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.util.system.getResourceColor
import yokai.i18n.MR

/**
 * Per-category sort picker, opened from the sort affordance on a [LibraryCategoryHeader]. Modal
 * bottom sheet titled "Sort by", with all nine [LibrarySort] modes as rows. The currently
 * selected mode is tinted with the primary color and (for directional modes) shows an arrow
 * indicating ascending vs descending. Tapping the same mode flips direction; tapping a different
 * mode switches to its default direction — both behaviors handled by the caller (the screen
 * model's `setSort` method).
 *
 * Visual port of the legacy `MaterialMenuSheet` opened from
 * `LibraryHeaderHolder.kt:275-293`'s "Sort by" entry. Container colour reads
 * `?attr/background` directly to stay flush with the library body across Reikai themes (same
 * pattern as [GroupLibraryByPicker] and [CategoryPickerSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySortSheet(
    currentMode: LibrarySort?,
    currentAscending: Boolean,
    isDynamic: Boolean,
    onModeSelected: (LibrarySort) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    // Reikai's `?attr/background` is set per-theme variant; read it directly so the sheet stays
    // flush with the library body across all themes (same pattern as CategoryPickerSheet at
    // CategoryPickerSheet.kt:61 and GroupLibraryByPicker). M3's bridge does not surface this
    // attr as any ColorScheme token, so any M3 token would drift slightly on user themes.
    val sheetContainerColor = remember(context) {
        Color(context.getResourceColor(R.attr.background))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetContainerColor,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = stringResource(MR.strings.sort_by),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LibrarySort.entries.forEach { mode ->
                SortRow(
                    label = stringResource(mode.stringRes(isDynamic)),
                    iconRes = mode.iconRes(isDynamic),
                    isSelected = mode == currentMode,
                    ascending = currentAscending,
                    showDirection = mode == currentMode && mode.isDirectional,
                    isInverted = mode.hasInvertedSort,
                    onClick = { onModeSelected(mode) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SortRow(
    label: String,
    iconRes: Int,
    isSelected: Boolean,
    ascending: Boolean,
    showDirection: Boolean,
    isInverted: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = if (isSelected) accent else MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (isSelected) accent else rest,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        if (showDirection) {
            // Legacy arrow convention: ascending = downward (small to large as you scroll down);
            // descending = upward. Modes flagged `hasInvertedSort` (LastRead, LatestChapter, etc.)
            // flip the icon meaning so the visual direction tracks the perceived "natural" order
            // (e.g. LastRead descending shows downward = newest at top, oldest at bottom).
            val pointsDown = ascending xor isInverted
            Icon(
                imageVector = if (pointsDown) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
