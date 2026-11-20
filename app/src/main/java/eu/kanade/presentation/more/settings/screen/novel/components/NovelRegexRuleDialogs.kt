package eu.kanade.presentation.more.settings.screen.novel.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.more.settings.screen.novel.NovelRegexPreview
import reikai.novel.content.NovelRegexReplacement
import reikai.novel.content.NovelRegexReplacements
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

/**
 * Adds a rule when [rule] is null and edits it otherwise. Everything typed here is previewed through
 * the reader's own kernel: [onPreview] asks for it and [preview] is the answer, so the sample result
 * cannot disagree with what a chapter gets.
 */
@Composable
fun NovelRegexRuleEditDialog(
    rule: NovelRegexReplacement?,
    preview: NovelRegexPreview?,
    onDismissRequest: () -> Unit,
    onSave: (NovelRegexReplacement) -> Unit,
    onPreview: (NovelRegexReplacement, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(rule?.title.orEmpty()) }
    var pattern by rememberSaveable { mutableStateOf(rule?.pattern.orEmpty()) }
    var replacement by rememberSaveable { mutableStateOf(rule?.replacement.orEmpty()) }
    var isRegex by rememberSaveable { mutableStateOf(rule?.isRegex ?: true) }
    var matchWholeWord by rememberSaveable { mutableStateOf(rule?.matchWholeWord ?: false) }
    var caseSensitive by rememberSaveable { mutableStateOf(rule?.caseSensitive ?: false) }
    var sample by rememberSaveable { mutableStateOf("") }
    // Held across recompositions: a fresh id per frame would make every save look like a new rule.
    val newId = remember { UUID.randomUUID().toString() }

    val edited = NovelRegexReplacement(
        title = title.trim(),
        pattern = pattern,
        replacement = replacement,
        enabled = rule?.enabled ?: true,
        isRegex = isRegex,
        matchWholeWord = matchWholeWord && !isRegex,
        caseSensitive = caseSensitive,
        id = rule?.id ?: newId,
    )
    // Asked on every edit rather than behind a button, so a pattern that stops compiling says so while it
    // is being typed and the Save button below can refuse it on the same signal. The title has no part in
    // what the rule does, so naming it asks nothing again.
    val asked = edited.copy(title = "")
    LaunchedEffect(asked, sample) { if (asked.pattern.isNotBlank()) onPreview(asked, sample) }
    // An answer to an earlier edit is not this one's, so Save waits for the current answer.
    val result = preview?.takeIf { pattern.isNotBlank() && it.rule == asked && it.sample == sample }?.result
    val error = result?.exceptionOrNull()
    val replacementError = error as? NovelRegexReplacements.InvalidReplacementException
    val patternError = error.takeIf { replacementError == null }
    val checkboxRow = Modifier.padding(top = MaterialTheme.padding.small)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                // Blank rather than empty: the pipeline skips a rule whose pattern is only whitespace,
                // so accepting one here would save a rule the reader silently never applies.
                enabled = title.isNotBlank() && pattern.isNotBlank() && result?.isSuccess == true,
                onClick = {
                    onSave(edited)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(
                    if (rule == null) MR.strings.action_add else MR.strings.action_edit,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(MR.strings.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(MR.strings.novel_regex_find)) },
                    isError = patternError != null,
                    supportingText = patternError?.let { e ->
                        {
                            Text(
                                if (e is NovelRegexReplacements.TooSlowException) {
                                    stringResource(MR.strings.novel_regex_too_slow)
                                } else {
                                    e.message.orEmpty()
                                },
                            )
                        }
                    },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(MR.strings.novel_regex_replace_with)) },
                    isError = replacementError != null,
                    supportingText = replacementError?.let { { Text(it.message.orEmpty()) } },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
                LabeledCheckbox(
                    label = stringResource(MR.strings.novel_regex_use_pattern),
                    checked = isRegex,
                    onCheckedChange = { isRegex = it },
                    modifier = checkboxRow,
                )
                // Whole words only has no meaning once the pattern can say so itself, so it is hidden
                // rather than shown doing nothing.
                if (!isRegex) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.novel_regex_whole_words),
                        checked = matchWholeWord,
                        onCheckedChange = { matchWholeWord = it },
                        modifier = checkboxRow,
                    )
                }
                LabeledCheckbox(
                    label = stringResource(MR.strings.novel_regex_match_case),
                    checked = caseSensitive,
                    onCheckedChange = { caseSensitive = it },
                    modifier = checkboxRow,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = { Text(stringResource(MR.strings.novel_regex_sample)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                result?.getOrNull()?.takeIf { sample.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    )
                }
            }
        },
    )
}
