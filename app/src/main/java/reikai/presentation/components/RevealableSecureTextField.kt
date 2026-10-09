package reikai.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
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
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff

/**
 * A full-width password field with a show/hide toggle, the last field of a sign-in dialog. Mihon's
 * tracker sign-in draws the same field inline in a private dialog, which stays in upstream's shape.
 */
@Composable
fun RevealableSecureTextField(
    state: TextFieldState,
    label: String,
    isError: Boolean = false,
) {
    var hidden by remember { mutableStateOf(true) }
    OutlinedSecureTextField(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
        state = state,
        label = { Text(text = label) },
        trailingIcon = {
            IconButton(onClick = { hidden = !hidden }) {
                Icon(
                    imageVector = if (hidden) {
                        MaterialSymbols.RoundedFilled.Visibility
                    } else {
                        MaterialSymbols.RoundedFilled.VisibilityOff
                    },
                    contentDescription = null,
                )
            }
        },
        textObfuscationMode = if (hidden) TextObfuscationMode.Hidden else TextObfuscationMode.Visible,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        isError = isError,
    )
}
