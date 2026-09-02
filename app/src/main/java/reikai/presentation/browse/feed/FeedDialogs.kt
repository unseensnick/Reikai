package reikai.presentation.browse.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import reikai.domain.source.model.SavedSearch
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Step one of adding a row: which source it watches. */
@Composable
fun FeedSourcePickerDialog(
    sources: List<BrowseSearchRow>,
    onDismissRequest: () -> Unit,
    onPick: (BrowseSearchRow) -> Unit,
) {
    PickerDialog(
        title = stringResource(MR.strings.action_add_to_feed),
        onDismissRequest = onDismissRequest,
        options = sources.map { it.name },
        onPick = { index -> onPick(sources[index]) },
    )
}

/**
 * Step two: the source's own listing, or one of the searches saved on it. A source with no saved
 * searches still shows this, so the one option it does have is confirmed rather than assumed.
 */
@Composable
fun FeedSearchPickerDialog(
    source: BrowseSearchRow,
    searches: List<SavedSearch>,
    onDismissRequest: () -> Unit,
    onPick: (SavedSearch?) -> Unit,
) {
    PickerDialog(
        title = source.name,
        onDismissRequest = onDismissRequest,
        options = listOf(stringResource(MR.strings.feed_latest_row)) + searches.map { it.name },
        onPick = { index -> onPick(searches.getOrNull(index - 1)) },
    )
}

@Composable
fun FeedRemoveDialog(
    name: String,
    onDismissRequest: () -> Unit,
    onRemove: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onRemove()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_remove)) },
        text = { Text(text = stringResource(MR.strings.feed_remove_confirmation, name)) },
    )
}

@Composable
fun FeedFullDialog(onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_add_to_feed)) },
        text = { Text(text = stringResource(MR.strings.feed_full, MAX_FEED_ROWS)) },
    )
}

/** A plain list of choices, scrollable because a source list runs long. */
@Composable
private fun PickerDialog(
    title: String,
    onDismissRequest: () -> Unit,
    options: List<String>,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(options.size) { index ->
                    Text(
                        text = options[index],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(vertical = MaterialTheme.padding.medium),
                    )
                }
            }
        },
    )
}
