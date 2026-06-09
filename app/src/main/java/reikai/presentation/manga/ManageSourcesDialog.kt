package reikai.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel.MergeSourceInfo
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Lists the sources merged into one series so the user can split a source back out, or split it and
 * remove it from the library. Net-new Reikai UI for the pref-based merge feature.
 */
@Composable
fun ManageSourcesDialog(
    sources: List<MergeSourceInfo>,
    onDismissRequest: () -> Unit,
    onSplit: (List<Long>) -> Unit,
    onRemoveFromLibrary: (List<Long>) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val checked = remember { mutableStateMapOf<Long, Boolean>() }
    val selected = sources.mapNotNull { source -> source.mangaId.takeIf { checked[it] == true } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_manage_sources)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checked[source.mangaId] = !(checked[source.mangaId] ?: false) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked[source.mangaId] ?: false,
                            onCheckedChange = { checked[source.mangaId] = it },
                        )
                        Text(
                            text = if (source.isCurrent) {
                                stringResource(MR.strings.merge_sources_current, source.sourceName)
                            } else {
                                source.sourceName
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                // Group-wide shortcut, independent of the per-source checkboxes above.
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = onRemoveAll,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(MR.strings.merge_sources_remove_all_action))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSplit(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(stringResource(MR.strings.merge_sources_split_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onRemoveFromLibrary(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(stringResource(MR.strings.merge_sources_remove_action))
            }
        },
    )
}
