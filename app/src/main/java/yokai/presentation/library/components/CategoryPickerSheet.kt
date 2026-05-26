package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.ILibraryCategory
import eu.kanade.tachiyomi.util.system.getResourceColor
import yokai.i18n.MR

/**
 * Category picker invoked from the [CategoryHopper] center button. Mirrors the legacy
 * `MaterialMenuSheet` used by `categoryButton.setOnClickListener` for jump-to-category: title
 * at top, compact 48dp rows with 14sp `onBackground` text, optional "(count)" suffix when the
 * legacy `categoryNumberOfItems` preference is on, and an arrow indicator on the active row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <C : ILibraryCategory> CategoryPickerSheet(
    categories: List<C>,
    itemCounts: Map<Int, Int>,
    showItemCounts: Boolean,
    activeCategoryId: Int?,
    onSelect: (C) -> Unit,
    onDismiss: () -> Unit,
) {
    // Faithful port of the legacy MaterialMenuSheet sizing: opens fully expanded showing as
    // much content as the cap allows, drag-down or tap-outside dismisses. No drag handle (the
    // title row is the affordance) and content capped at half-screen so long category lists
    // scroll inside. sheetMaxWidth left at the M3 default (640.dp) so tablets see the sheet
    // centered. containerColor reads ?attr/background straight off the theme (same pattern as
    // the top bar) — that's the legacy Reikai custom attr setting the library body color, and
    // createMdc3Theme doesn't surface it as an M3 ColorScheme token, so no token would have
    // matched. Reading the same attr the library body uses keeps the sheet visually flush
    // with the content above it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    val context = LocalContext.current
    val sheetContainerColor = remember(context) {
        Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.background))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = sheetContainerColor,
    ) {
        // Centered title at 18sp matches the legacy MaterialMenuSheet title position and size
        // (the legacy sheet uses textAppearanceTitleLarge on a centered TextView, 18sp on
        // most themes). No divider underneath; legacy uses spacing alone as the visual break.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.strings.jump_to_category),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                textAlign = TextAlign.Center,
            )
        }
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
    // Active row indicates state via primary-tinted text rather than a trailing arrow; matches
    // legacy MaterialMenuSheet where the selected row's TextView is colored with the theme's
    // accent.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = if (count != null) "$name ($count)" else name,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
