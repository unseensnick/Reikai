package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.RadioButtonUnchecked
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippetKind
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB
import java.util.UUID

/**
 * The WebView reader's CSS or JavaScript snippets. Novel-only and WebView-only by mechanism: the text
 * renderer has no stylesheet or script to add them to, and a manga page is an image.
 */
data class NovelCodeSnippetsScreen(private val kind: NovelSnippetKind) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<NovelCodeSnippetsViewModel, NovelCodeSnippetsViewModel.Factory>(
            key = kind.name,
        ) {
            create(kind)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lazyListState = rememberLazyListState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(kind.titleRes),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_add)) },
                    icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { viewModel.showDialog(NovelCodeSnippetDialog.Edit(null)) },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            },
        ) { paddingValues ->
            if (state.snippets.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_empty_novel_snippets,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = paddingValues + topSmallPaddingValues,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                ) {
                    items(state.snippets, key = { it.id }) { snippet ->
                        SnippetItem(
                            snippet = snippet,
                            onClick = { viewModel.showDialog(NovelCodeSnippetDialog.Edit(snippet)) },
                            onToggle = { viewModel.toggle(snippet) },
                            onDelete = { viewModel.showDialog(NovelCodeSnippetDialog.Delete(snippet)) },
                        )
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            null -> {}
            is NovelCodeSnippetDialog.Edit -> SnippetEditDialog(
                snippet = dialog.snippet,
                kind = kind,
                onDismissRequest = viewModel::dismissDialog,
                onSave = viewModel::save,
            )
            is NovelCodeSnippetDialog.Delete -> AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                confirmButton = {
                    TextButton(onClick = { viewModel.delete(dialog.snippet) }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                title = { Text(text = stringResource(MR.strings.action_delete)) },
                text = {
                    Text(text = stringResource(MR.strings.novel_snippet_delete_confirmation, dialog.snippet.title))
                },
            )
        }
    }
}

private val NovelSnippetKind.titleRes
    get() = when (this) {
        NovelSnippetKind.CSS -> MR.strings.pref_novel_css_snippets
        NovelSnippetKind.JS -> MR.strings.pref_novel_js_snippets
    }

/** The tick toggles the snippet; the rest of the row opens it for editing. */
@Composable
private fun SnippetItem(
    snippet: NovelCodeSnippet,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (snippet.enabled) {
                        MaterialSymbols.RoundedFilled.CheckCircle
                    } else {
                        MaterialSymbols.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = stringResource(MR.strings.action_enable),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.padding.small)
                    .alpha(if (snippet.enabled) 1f else DISABLED_ALPHA),
            ) {
                Text(text = snippet.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = snippet.code.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = runOnAppend, onCheckedChange = { runOnAppend = it })
                        Text(
                            text = stringResource(MR.strings.novel_snippet_run_on_append),
                            modifier = Modifier.padding(start = MaterialTheme.padding.small),
                        )
                    }
                }
            }
        },
    )
}

private const val DISABLED_ALPHA = 0.5f
