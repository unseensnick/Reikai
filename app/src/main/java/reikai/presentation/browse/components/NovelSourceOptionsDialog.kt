package reikai.presentation.browse.components

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
 * Long-press options for a novel source: pin/unpin and enable/disable. Novel twin of Mihon's
 * [eu.kanade.presentation.browse.SourceOptionsDialog]; a disabled source stays in the list dimmed
 * (so the toggle reads "Enable") and is excluded from global search.
 */
@Composable
fun NovelSourceOptionsDialog(
    sourceName: String,
    isPinned: Boolean,
    isDisabled: Boolean,
    onClickPin: () -> Unit,
    onClickToggleDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = sourceName) },
        text = {
            Column {
                Text(
                    text = stringResource(if (isPinned) MR.strings.action_unpin else MR.strings.action_pin),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                Text(
                    text = stringResource(if (isDisabled) MR.strings.action_enable else MR.strings.action_disable),
                    modifier = Modifier
                        .clickable(onClick = onClickToggleDisable)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}
