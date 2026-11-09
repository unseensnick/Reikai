package reikai.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** A one-line name to edit, starting from [current]; [confirmText] stays off while the name is blank. */
@Composable
fun NameDialog(
    title: String,
    current: String,
    confirmText: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmText) }
        },
        dismissButton = dismissButton,
    )
}
