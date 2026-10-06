package eu.kanade.presentation.more.settings.screen.novel.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.novel.NovelToggleListDialog
import eu.kanade.presentation.more.settings.screen.novel.NovelToggleListViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.RadioButtonUnchecked
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import reikai.novel.content.NovelStoredItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * A screen of entries the user writes, each switched on and off in place: the find-and-replace rules
 * and the code snippets. [summary] is the line under an entry's title, and [editDialog] adds an entry
 * when handed null and edits it otherwise.
 */
@Composable
fun <T : NovelStoredItem<T>> NovelToggleList(
    viewModel: NovelToggleListViewModel<T>,
    title: String,
    emptyRes: StringResource,
    deleteConfirmationRes: StringResource,
    summary: @Composable (T) -> String,
    editDialog: @Composable (T?) -> Unit,
    summaryFontFamily: FontFamily? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(title = title, navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                text = { Text(text = stringResource(MR.strings.action_add)) },
                icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                onClick = { viewModel.showDialog(NovelToggleListDialog.Edit(null)) },
                expanded = lazyListState.shouldExpandFAB(),
            )
        },
    ) { paddingValues ->
        if (state.items.isEmpty()) {
            EmptyScreen(stringRes = emptyRes, modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                state = lazyListState,
                contentPadding = paddingValues + topSmallPaddingValues,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            ) {
                items(state.items, key = { it.id }) { item ->
                    ToggleItem(
                        title = item.title,
                        summary = summary(item),
                        summaryFontFamily = summaryFontFamily,
                        enabled = item.enabled,
                        onClick = { viewModel.showDialog(NovelToggleListDialog.Edit(item)) },
                        onToggle = { viewModel.toggle(item) },
                        onDelete = { viewModel.showDialog(NovelToggleListDialog.Delete(item)) },
                    )
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        null -> {}
        is NovelToggleListDialog.Edit -> editDialog(dialog.item)
        is NovelToggleListDialog.Delete -> NovelDeleteDialog(
            text = stringResource(deleteConfirmationRes, dialog.item.title),
            onDismissRequest = viewModel::dismissDialog,
            onConfirm = { viewModel.delete(dialog.item) },
        )
    }
}

/** The tick switches the entry; the rest of the row opens it for editing. */
@Composable
private fun ToggleItem(
    title: String,
    summary: String,
    summaryFontFamily: FontFamily?,
    enabled: Boolean,
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
                    imageVector = if (enabled) {
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
                    .alpha(if (enabled) 1f else DISABLED_ALPHA),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = summaryFontFamily,
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

/** Asks before something the user made is removed: a rule, a snippet or an added font. */
@Composable
fun NovelDeleteDialog(text: String, onDismissRequest: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_delete)) },
        text = { Text(text = text) },
    )
}
