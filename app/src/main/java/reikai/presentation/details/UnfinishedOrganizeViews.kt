package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A line above the list while the series is refused for an organize or an import Replace no retry has resolved;
 * a tap offers [ForgetUnfinishedDialog]. Absent when [onForget] is null.
 */
internal fun LazyListScope.unfinishedOrganizeItem(onForget: (() -> Unit)?) {
    if (onForget == null) return
    item(key = "rk-unfinished-organize", contentType = "rk-unfinished-organize") {
        Text(
            stringResource(MR.strings.local_series_waits_for_repair),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onForget)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Asks before forgetting the unfinished organize, saying that no file moves. */
@Composable
internal fun ForgetUnfinishedDialog(onDismissRequest: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_series_forget_unfinished)) },
        text = { Text(stringResource(MR.strings.local_series_forget_unfinished_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(MR.strings.action_forget)) } },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
