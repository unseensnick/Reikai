package reikai.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Long-press options for a browse source (manga or novel): pin/unpin, disable when
 * [showToggleDisable], and incognito when [showToggleIncognito]. The disable row always reads
 * "Disable", because a disabled source is not listed. The manga/novel option lists can no longer drift.
 */
@Composable
fun EntrySourceOptionsDialog(
    title: String,
    isPinned: Boolean,
    showToggleDisable: Boolean,
    onClickPin: () -> Unit,
    showToggleIncognito: Boolean,
    isIncognito: Boolean,
    onClickToggleDisable: () -> Unit,
    onClickToggleIncognito: () -> Unit,
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
                        text = stringResource(MR.strings.action_disable),
                        modifier = Modifier
                            .clickable(onClick = onClickToggleDisable)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
                if (showToggleIncognito) {
                    // Worded and drawn as the extension details screen's own incognito switch.
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onClickToggleIncognito)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(MR.strings.pref_incognito_mode),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = isIncognito, onCheckedChange = null)
                    }
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}
