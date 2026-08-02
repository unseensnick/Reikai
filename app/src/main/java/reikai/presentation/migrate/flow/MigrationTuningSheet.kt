package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The pre-list search options sheet, shared by both content types: extra query and the hide toggles
 * apply to both; the smart-match half renders only where the adapter supports it (manga), since
 * those options run on the manga smart-search engine.
 */
@Composable
fun MigrationTuningSheet(
    tuning: MigrationTuning,
    supportsSmartMatch: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (MigrationTuning) -> Unit,
) {
    var extraQuery by remember { mutableStateOf(tuning.extraQuery.orEmpty()) }
    var hideUnmatched by remember { mutableStateOf(tuning.hideUnmatched) }
    var hideWithoutUpdates by remember { mutableStateOf(tuning.hideWithoutUpdates) }
    var deepSearch by remember { mutableStateOf(tuning.deepSearch) }
    var prioritizeByChapters by remember { mutableStateOf(tuning.prioritizeByChapters) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(MR.strings.migrationFlow_searchOptionsTitle),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = extraQuery,
                onValueChange = { extraQuery = it },
                label = { Text(text = stringResource(MR.strings.migrationConfigScreen_additionalSearchQueryLabel)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                label = stringResource(MR.strings.migrationConfigScreen_hideUnmatchedTitle),
                checked = hideUnmatched,
                onCheckedChange = { hideUnmatched = it },
            )
            ToggleRow(
                label = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
                checked = hideWithoutUpdates,
                onCheckedChange = { hideWithoutUpdates = it },
            )
            if (supportsSmartMatch) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    label = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                    checked = deepSearch,
                    onCheckedChange = { deepSearch = it },
                )
                ToggleRow(
                    label = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                    checked = prioritizeByChapters,
                    onCheckedChange = { prioritizeByChapters = it },
                )
            } else {
                Text(
                    text = stringResource(MR.strings.migrationFlow_smartMatchUnavailableNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = {
                    onApply(
                        MigrationTuning(
                            extraQuery = extraQuery.trim().ifBlank { null },
                            deepSearch = deepSearch,
                            prioritizeByChapters = prioritizeByChapters,
                            hideUnmatched = hideUnmatched,
                            hideWithoutUpdates = hideWithoutUpdates,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText))
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
