package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Category
import yokai.i18n.MR

/**
 * Category picker invoked from the [CategoryHopper] center button. Mirrors the legacy
 * `MaterialMenuSheet` used by `categoryButton.setOnClickListener` for jump-to-category: title
 * at top, compact 48dp rows with 14sp `onBackground` text, optional "(count)" suffix when the
 * legacy `categoryNumberOfItems` preference is on, and an arrow indicator on the active row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<Category>,
    itemCounts: Map<Int, Int>,
    showItemCounts: Boolean,
    activeCategoryId: Int?,
    onSelect: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    // Faithful port of the legacy MaterialMenuSheet sizing: opens fully expanded showing as
    // much content as the cap allows, drag-down or tap-outside dismisses. No drag handle (the
    // title row is the affordance), full-width on tablets, and content capped at half-screen
    // so long category lists scroll inside instead of pushing the sheet to ~95% height.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        sheetMaxWidth = Dp.Unspecified,
    ) {
        Text(
            text = stringResource(MR.strings.jump_to_category),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight),
        ) {
            items(categories, key = { it.id ?: 0 }) { category ->
                CategoryPickerRow(
                    name = category.name,
                    count = if (showItemCounts) itemCounts[category.id ?: 0] else null,
                    isActive = category.id == activeCategoryId,
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun CategoryPickerRow(
    name: String,
    count: Int?,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count != null) "$name ($count)" else name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isActive) {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
