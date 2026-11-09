package reikai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.formatChapterNumber
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.RemoveDone
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The volume chips above a local series' list: every volume, then each one alone. */
internal fun LazyListScope.volumeChipsItem(view: VolumeView, onPick: (String?) -> Unit) {
    item(key = "rk-volume-chips", contentType = "rk-volume-chips") {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            item {
                FilterChip(
                    selected = view.picked == null,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(MR.strings.all_volumes)) },
                )
            }
            items(view.chips, key = { it.key }) { chip ->
                FilterChip(
                    selected = view.picked == chip.key,
                    onClick = { onPick(chip.key) },
                    label = { Text(volumeLabel(chip.label)) },
                )
            }
        }
    }
}

/** A volume's header: its label and read count; a tap selects its chapters where [onSelect] is given, the
 *  button marks them read or unread. */
@Composable
internal fun VolumeHeader(
    volume: EntryChapterListItem.Volume,
    onSelect: (() -> Unit)?,
    onMarkRead: (Boolean) -> Unit,
) {
    val allRead = volume.readCount == volume.chapterIds.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(volumeLabel(volume.label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(MR.strings.volume_read_count, volume.readCount, volume.chapterIds.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMarkRead(!allRead) }) {
            Icon(
                imageVector = if (allRead) MaterialSymbols.Rounded.RemoveDone else MaterialSymbols.Rounded.DoneAll,
                contentDescription = stringResource(
                    if (allRead) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                ),
            )
        }
    }
}

@Composable
private fun volumeLabel(label: VolumeLabel): String = when (label) {
    is VolumeLabel.Number -> stringResource(MR.strings.volume_label, formatChapterNumber(label.number))
    is VolumeLabel.Name -> label.name
}
