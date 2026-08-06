package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The search options settled before a migration runs: an extra query, the smart-match options, and
 * the two hide toggles. The toggles apply as they are made; the extra query is hosted by the CALLER,
 * which commits it on IME done and on a real dismissal, so a rotation neither loses the draft nor
 * persists a half-typed one. Options that need the smart-search engine are absent for a content type
 * that has none, with a line saying so, rather than switches that do nothing.
 */
@Composable
fun MigrationTuningSheet(
    tuning: MigrationTuning,
    query: String,
    onQueryChange: (String) -> Unit,
    onCommitQuery: () -> Unit,
    matchStrategy: MatchStrategy,
    onApply: (MigrationTuning) -> Unit,
) {
    // LabeledCheckbox brings no horizontal inset of its own, so without this the boxes sit against
    // the screen edge while the title and query field above them keep the sheet margin.
    val checkboxRow = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal)
    Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium)) {
        Text(
            text = stringResource(MR.strings.migrationFlow_searchOptionsTitle),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(text = stringResource(MR.strings.migrationConfigScreen_additionalSearchQueryLabel)) },
            supportingText = {
                Text(text = stringResource(MR.strings.migrationConfigScreen_additionalSearchQuerySupportingText))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommitQuery() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
        )

        if (matchStrategy is MatchStrategy.Smart) {
            // Upstream's warning, and it belongs to these two options only: both multiply the
            // requests one row makes, which is what gets a source to start refusing them.
            WarningLine(text = stringResource(MR.strings.migrationConfigScreen_enhancedOptionsWarning))
            LabeledCheckbox(
                label = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                checked = tuning.deepSearch,
                onCheckedChange = { onApply(tuning.copy(deepSearch = it)) },
                modifier = checkboxRow,
            )
            LabeledCheckbox(
                label = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                checked = tuning.prioritizeByChapters,
                onCheckedChange = { onApply(tuning.copy(prioritizeByChapters = it)) },
                modifier = checkboxRow,
            )
        } else {
            Text(
                text = stringResource(MR.strings.migrationFlow_smartMatchUnavailableNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = 8.dp,
                ),
            )
        }

        LabeledCheckbox(
            label = stringResource(MR.strings.migrationConfigScreen_hideUnmatchedTitle),
            checked = tuning.hideUnmatched,
            onCheckedChange = { onApply(tuning.copy(hideUnmatched = it)) },
            modifier = checkboxRow,
        )
        // Offered for both content types: the counts it compares arrive at search time on manga and
        // from the count peek on novels, and a row whose count is still unknown stays visible, so
        // the toggle is never silently wrong where a source does not report one.
        LabeledCheckbox(
            label = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
            checked = tuning.hideWithoutUpdates,
            onCheckedChange = { onApply(tuning.copy(hideWithoutUpdates = it)) },
            modifier = checkboxRow,
        )
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
