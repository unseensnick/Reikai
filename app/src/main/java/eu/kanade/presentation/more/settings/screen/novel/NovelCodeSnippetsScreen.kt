package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.more.settings.screen.novel.components.NovelToggleList
import eu.kanade.presentation.util.Screen
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippetKind
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

/**
 * The WebView reader's CSS or JavaScript snippets. Novel-only and WebView-only by mechanism: the text
 * renderer has no stylesheet or script to add them to, and a manga page is an image.
 */
data class NovelCodeSnippetsScreen(private val kind: NovelSnippetKind) : Screen() {

    @Composable
    override fun Content() {
        val viewModel = assistedMetroViewModel<NovelCodeSnippetsViewModel, NovelCodeSnippetsViewModel.Factory>(
            key = kind.name,
        ) {
            create(kind)
        }
        NovelToggleList(
            viewModel = viewModel,
            title = stringResource(kind.titleRes),
            emptyRes = MR.strings.information_empty_novel_snippets,
            deleteConfirmationRes = MR.strings.novel_snippet_delete_confirmation,
            summary = { snippet -> snippet.code.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() },
            summaryFontFamily = FontFamily.Monospace,
            editDialog = { snippet ->
                SnippetEditDialog(
                    snippet = snippet,
                    kind = kind,
                    onDismissRequest = viewModel::dismissDialog,
                    onSave = viewModel::save,
                )
            },
        )
    }
}

private val NovelSnippetKind.titleRes
    get() = when (this) {
        NovelSnippetKind.CSS -> MR.strings.pref_novel_css_snippets
        NovelSnippetKind.JS -> MR.strings.pref_novel_js_snippets
    }

/** Adds a snippet when [snippet] is null and edits it otherwise. */
@Composable
private fun SnippetEditDialog(
    snippet: NovelCodeSnippet?,
    kind: NovelSnippetKind,
    onDismissRequest: () -> Unit,
    onSave: (NovelCodeSnippet) -> Unit,
) {
    var title by remember { mutableStateOf(snippet?.title.orEmpty()) }
    var code by remember { mutableStateOf(snippet?.code.orEmpty()) }
    var runOnAppend by remember { mutableStateOf(snippet?.runOnAppend ?: false) }
    // Held across recompositions: a fresh id per frame would make every save look like a new snippet.
    val newId = remember { UUID.randomUUID().toString() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && code.isNotBlank(),
                onClick = {
                    onSave(
                        NovelCodeSnippet(
                            title = title.trim(),
                            code = code,
                            enabled = snippet?.enabled ?: true,
                            runOnAppend = runOnAppend && kind == NovelSnippetKind.JS,
                            id = snippet?.id ?: newId,
                        ),
                    )
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
        title = { Text(text = stringResource(if (snippet == null) MR.strings.action_add else MR.strings.action_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(MR.strings.novel_regex_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(MR.strings.novel_snippet_code)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    // Plain ASCII keyboard behaviour: no autocorrect rewriting a selector or a keyword.
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
                // A stylesheet already covers every chapter added to the page, so only a script asks.
                if (kind == NovelSnippetKind.JS) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.novel_snippet_run_on_append),
                        checked = runOnAppend,
                        onCheckedChange = { runOnAppend = it },
                        modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    )
                }
            }
        },
    )
}
