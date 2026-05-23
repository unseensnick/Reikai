package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Category
import yokai.i18n.MR

/**
 * Bottom-sheet category picker invoked from the [CategoryHopper] center button. Mirrors the
 * legacy `MaterialMenuSheet` opened by `categoryButton`: a vertical list of category names,
 * the currently active category highlighted, tap to jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<Category>,
    activeCategoryId: Int?,
    onSelect: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(MR.strings.jump_to_category),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(categories, key = { it.id ?: 0 }) { category ->
                val isActive = category.id == activeCategoryId
                ListItem(
                    headlineContent = { Text(category.name) },
                    modifier = Modifier.clickable { onSelect(category) },
                    colors = if (isActive) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                )
            }
        }
    }
}
