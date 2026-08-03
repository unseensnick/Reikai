package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The search options behind the migration list: an extra query, the smart-match options, and the two
 * hide toggles. The toggles apply as they are made; the extra query applies on IME done and when the
 * sheet closes, because applying a search-affecting change rebuilds every non-migrated row and
 * restarts the batch, which per keystroke meant one full restart per character typed.
 *
 * Options that need the smart-search engine are simply absent for a content type that has none,
 * with a line saying so, rather than rendered as switches that do nothing.
 */
@Composable
fun MigrationTuningSheet(
    tuning: MigrationTuning,
    supportsSmartMatch: Boolean,
    supportsChapterComparison: Boolean,
    onApply: (MigrationTuning) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(tuning.extraQuery.orEmpty()) }
    val currentQuery by rememberUpdatedState(query)
    val currentTuning by rememberUpdatedState(tuning)
    val currentOnApply by rememberUpdatedState(onApply)
    val commitQuery = {
        val trimmed = currentQuery.trim().takeIf(String::isNotBlank)
        if (trimmed != currentTuning.extraQuery) {
            currentOnApply(currentTuning.copy(extraQuery = trimmed))
        }
    }
    // The sheet closing is the other commit point; rememberUpdatedState keeps the disposed lambda
    // reading the LAST typed value, not the first composition's.
    DisposableEffect(Unit) {
        onDispose { commitQuery() }
    }

    Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.medium)) {
        Text(
            text = stringResource(MR.strings.migrationFlow_searchOptionsTitle),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(text = stringResource(MR.strings.migrationConfigScreen_additionalSearchQueryLabel)) },
            supportingText = {
                Text(text = stringResource(MR.strings.migrationConfigScreen_additionalSearchQuerySupportingText))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitQuery() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
        )

        if (supportsSmartMatch) {
            LabeledCheckbox(
                label = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                checked = tuning.deepSearch,
                onCheckedChange = { onApply(tuning.copy(deepSearch = it)) },
            )
            LabeledCheckbox(
                label = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                checked = tuning.prioritizeByChapters,
                onCheckedChange = { onApply(tuning.copy(prioritizeByChapters = it)) },
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
        )
        // Hiding by "no new chapters" needs a chapter count from the search, which not every content
        // type's sources return.
        if (supportsChapterComparison) {
            LabeledCheckbox(
                label = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
                checked = tuning.hideWithoutUpdates,
                onCheckedChange = { onApply(tuning.copy(hideWithoutUpdates = it)) },
            )
        }
    }
}
