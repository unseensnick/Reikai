package reikai.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Jump-to-category picker opened from the hopper's center button. Lists whatever the library is
 * sectioned by, real DB categories or dynamic groups, and answers with the index to scroll to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReikaiCategoryPickerSheet(
    buckets: List<LibraryBucket>,
    getItemCount: (LibraryBucket) -> Int?,
    showItemCounts: Boolean,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    // Open scrolled to the section currently on screen (-1 when absent falls back to the top).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex.coerceAtLeast(0))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.strings.jump_to_category),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                textAlign = TextAlign.Center,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight),
        ) {
            itemsIndexed(buckets, key = { _, bucket -> bucket.key }) { index, bucket ->
                CategoryPickerRow(
                    name = bucket.visualLabel,
                    count = if (showItemCounts) getItemCount(bucket) else null,
                    isActive = index == activeIndex,
                    onClick = { onSelect(index) },
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
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
