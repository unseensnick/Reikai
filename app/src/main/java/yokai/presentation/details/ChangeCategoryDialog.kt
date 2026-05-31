package yokai.presentation.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.database.models.Category

@Composable
fun ChangeCategoryDialog(
    allCategories: List<Category>,
    currentCategoryIds: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (selectedIds: List<Long>) -> Unit,
) {
    var selected by remember(currentCategoryIds) { mutableStateOf(currentCategoryIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set categories") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                allCategories.forEach { category ->
                    val catId = category.id?.toLong() ?: return@forEach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = catId in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + catId else selected - catId
                            },
                        )
                        Text(
                            text = category.name,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList()) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
