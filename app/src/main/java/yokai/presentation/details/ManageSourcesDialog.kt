package yokai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/** One grouped source in the Manage sources dialog. [mangaId] is the per-source library row;
 *  [isCurrent] marks the source the details screen is currently showing. */
data class ManageSourceItem(
    val mangaId: Long,
    val sourceName: String,
    val isCurrent: Boolean,
)

private enum class PendingSourceAction { Split, Remove }

/**
 * Compose rebuild of the legacy `ManageSourcesSheet`: a checklist of the sources merged into one
 * title, with two actions. **Split** ungroups the selected sources (they stay in the library);
 * **Remove from library** unfavorites them. Both confirm first. Pure UI: the caller (the
 * ScreenModel) owns the data and performs the actual merge / library writes.
 */
@Composable
fun ManageSourcesDialog(
    sources: List<ManageSourceItem>,
    onSplit: (List<Long>) -> Unit,
    onRemoveFromLibrary: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var pending by remember { mutableStateOf<PendingSourceAction?>(null) }
    val anySelected = selected.isNotEmpty()
    // The two action labels are long: they fit on one row on a tablet / unfolded foldable but
    // overflow and wrap badly on a phone / folded screen, so stack them there. Same 600dp
    // breakpoint the library display-options sheet uses.
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 600

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.manage_sources)) },
        text = {
            if (sources.isEmpty()) {
                Text(stringResource(MR.strings.remove_from_group))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    sources.forEach { item ->
                        val checked = item.mangaId in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - item.mangaId else selected + item.mangaId
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (item.isCurrent) "${item.sourceName}  ✓" else item.sourceName,
                                fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            // Read-only: the whole row is the click target.
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (wideLayout) {
                // Wide: one row, Cancel on the far left, the two actions on the right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = anySelected,
                        onClick = { pending = PendingSourceAction.Split },
                    ) { Text(stringResource(MR.strings.split_selected_sources)) }
                    TextButton(
                        enabled = anySelected,
                        onClick = { pending = PendingSourceAction.Remove },
                    ) { Text(stringResource(MR.strings.remove_selected_from_library)) }
                }
            } else {
                // Narrow: stack the actions right-aligned so the long labels don't wrap.
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        enabled = anySelected,
                        onClick = { pending = PendingSourceAction.Split },
                    ) { Text(stringResource(MR.strings.split_selected_sources)) }
                    TextButton(
                        enabled = anySelected,
                        onClick = { pending = PendingSourceAction.Remove },
                    ) { Text(stringResource(MR.strings.remove_selected_from_library)) }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )

    pending?.let { action ->
        val ids = selected.toList()
        val names = sources.filter { it.mangaId in selected }.joinToString("\n") { it.sourceName }
        val promptRes = when (action) {
            PendingSourceAction.Split -> MR.strings.remove_from_group
            PendingSourceAction.Remove -> MR.strings.remove_from_library
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            text = { Text("${stringResource(promptRes)}?\n$names") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        when (action) {
                            PendingSourceAction.Split -> onSplit(ids)
                            PendingSourceAction.Remove -> onRemoveFromLibrary(ids)
                        }
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }
}
