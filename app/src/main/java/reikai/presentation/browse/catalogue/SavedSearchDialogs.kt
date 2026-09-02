package reikai.presentation.browse.catalogue

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Names the search being saved. Follows the category-create dialog's shape, including refusing a name
 * already in use: the table does not enforce that, so this is where it holds.
 */
@Composable
fun SavedSearchCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
    existingNames: List<String>,
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name, existingNames) { existingNames.contains(name.trim()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !nameAlreadyExists,
                onClick = {
                    onCreate(name)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_save_search)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.focusRequester(focusRequester),
                value = name,
                onValueChange = { name = it },
                label = { Text(text = stringResource(MR.strings.name)) },
                supportingText = {
                    val message = if (nameAlreadyExists) {
                        MR.strings.error_saved_search_exists
                    } else {
                        MR.strings.information_required_plain
                    }
                    Text(text = stringResource(message))
                },
                isError = nameAlreadyExists,
                singleLine = true,
            )
        },
    )

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

@Composable
fun SavedSearchDeleteDialog(
    name: String,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_delete)) },
        text = { Text(text = stringResource(MR.strings.saved_search_delete_confirmation, name)) },
    )
}
