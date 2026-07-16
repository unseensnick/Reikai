package reikai.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Long-press options for a browse source (manga or novel): pin/unpin and, when [showToggleDisable],
 * enable/disable. The disable row's label flips on [isDisabled]; a caller that only ever disables
 * (manga's source list drops disabled sources) passes `isDisabled = false` so it always reads
 * "Disable". The manga/novel option lists can no longer drift.
 */
@Composable
fun EntrySourceOptionsDialog(
    title: String,
    isPinned: Boolean,
    showToggleDisable: Boolean,
    isDisabled: Boolean,
    onClickPin: () -> Unit,
    onClickToggleDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = title) },
        text = {
            Column {
                Text(
                    text = stringResource(if (isPinned) MR.strings.action_unpin else MR.strings.action_pin),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                if (showToggleDisable) {
                    Text(
                        text = stringResource(if (isDisabled) MR.strings.action_enable else MR.strings.action_disable),
                        modifier = Modifier
                            .clickable(onClick = onClickToggleDisable)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}
