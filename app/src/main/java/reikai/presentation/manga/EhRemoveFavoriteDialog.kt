package reikai.presentation.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

/**
 * RK: confirm removing a favorited E-Hentai gallery from the library, with an opt-in checkbox to
 * also remove it from the E-Hentai account favorites. Mirrors Mihon's tracker remove dialog
 * (DeletableTracker): local removal always happens; the remote removal is the opt-in.
 */
@Composable
fun EhRemoveFavoriteDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (removeFromAccount: Boolean) -> Unit,
) {
    var removeFromAccount by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.remove_from_library)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(MR.strings.eh_remove_favorite_text))
                LabeledCheckbox(
                    label = stringResource(MR.strings.eh_remove_favorite_remote),
                    checked = removeFromAccount,
                    onCheckedChange = { removeFromAccount = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(removeFromAccount) }) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
