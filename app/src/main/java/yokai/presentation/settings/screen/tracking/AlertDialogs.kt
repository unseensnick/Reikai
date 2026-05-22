package yokai.presentation.settings.screen.tracking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import kotlin.coroutines.resume
import yokai.domain.DialogHostState
import yokai.i18n.MR
import android.R as AR

data class TrackLoginCredentials(
    val username: String,
    val password: String,
)

suspend fun DialogHostState.awaitTrackLogin(
    serviceTitle: String,
    usernameLabel: String,
    initialUsername: String = "",
    initialPassword: String = "",
): TrackLoginCredentials? = dialog { cont ->
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val resume: (TrackLoginCredentials?) -> Unit = { value ->
        if (cont.isActive) cont.resume(value)
    }

    AlertDialog(
        onDismissRequest = { resume(null) },
        title = { Text(text = stringResource(MR.strings.log_in_to_, serviceTitle)) },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(usernameLabel) },
                    singleLine = true,
                )
                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(MR.strings.password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank(),
                onClick = { resume(TrackLoginCredentials(username, password)) },
            ) {
                Text(text = stringResource(MR.strings.log_in))
            }
        },
        dismissButton = {
            TextButton(onClick = { resume(null) }) {
                Text(text = androidx.compose.ui.res.stringResource(AR.string.cancel))
            }
        },
    )
}
