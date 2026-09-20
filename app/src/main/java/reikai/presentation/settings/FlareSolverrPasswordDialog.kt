package reikai.presentation.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Asks for the basic-auth password of a proxy in front of the bypass server.
 *
 * A dialog rather than a settings row because a row renders its stored value as the subtitle, at up
 * to ten lines, so an ordinary text preference would print the password on the screen. The field
 * shape is the one the tracker sign-in dialog uses.
 */
@Composable
fun FlareSolverrPasswordDialog(
    currentPassword: String,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val password = rememberTextFieldState(currentPassword)
    var hidePassword by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.pref_flaresolverr_password)) },
        text = {
            OutlinedSecureTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
                state = password,
                label = { Text(text = stringResource(MR.strings.password)) },
                trailingIcon = {
                    IconButton(onClick = { hidePassword = !hidePassword }) {
                        Icon(
                            imageVector = if (hidePassword) {
                                MaterialSymbols.Rounded.Visibility
                            } else {
                                MaterialSymbols.Rounded.VisibilityOff
                            },
                            contentDescription = null,
                        )
                    }
                },
                textObfuscationMode = if (hidePassword) {
                    TextObfuscationMode.Hidden
                } else {
                    TextObfuscationMode.Visible
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            // An empty field saves an empty password, which is how a reader clears it.
            TextButton(onClick = { onConfirm(password.text.toString()) }) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
    )
}
