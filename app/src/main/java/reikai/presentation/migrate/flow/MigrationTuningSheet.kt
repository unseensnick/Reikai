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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The pre-list search options sheet, shared by both content types: extra query and hide-unmatched
 * apply to both; the smart-match half and hide-without-updates render only where the adapter
 * supports them (manga), since they run on the smart-search engine / suggest-time chapter counts.
 */
@Composable
fun MigrationTuningSheet(
    tuning: MigrationTuning,
    supportsSmartMatch: Boolean,
    supportsChapterComparison: Boolean,
    onDismissRequest: () -> Unit,
    onApply: (MigrationTuning) -> Unit,
) {
    var extraQuery by rememberSaveable { mutableStateOf(tuning.extraQuery.orEmpty()) }
    var hideUnmatched by rememberSaveable { mutableStateOf(tuning.hideUnmatched) }
    var hideWithoutUpdates by rememberSaveable { mutableStateOf(tuning.hideWithoutUpdates) }
    var deepSearch by rememberSaveable { mutableStateOf(tuning.deepSearch) }
    var prioritizeByChapters by rememberSaveable { mutableStateOf(tuning.prioritizeByChapters) }

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
                supportingText = {
                    Text(text = stringResource(MR.strings.migrationConfigScreen_additionalSearchQuerySupportingText))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                label = stringResource(MR.strings.migrationConfigScreen_hideUnmatchedTitle),
                checked = hideUnmatched,
                onCheckedChange = { hideUnmatched = it },
            )
            if (supportsChapterComparison) {
                ToggleRow(
                    label = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
                    subtitle = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesSubtitle),
                    checked = hideWithoutUpdates,
                    onCheckedChange = { hideWithoutUpdates = it },
                )
            }
            if (supportsSmartMatch) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(MR.strings.migrationConfigScreen_enhancedOptionsWarning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ToggleRow(
                    label = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                    subtitle = stringResource(MR.strings.migrationConfigScreen_deepSearchModeSubtitle),
                    checked = deepSearch,
                    onCheckedChange = { deepSearch = it },
                )
                ToggleRow(
                    label = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                    subtitle = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersSubtitle),
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
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
