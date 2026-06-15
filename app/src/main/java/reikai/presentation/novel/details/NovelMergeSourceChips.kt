package reikai.presentation.novel.details

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Source switcher for a merged novel group: an "All" chip for the unified chapter list plus one chip
 * per grouped source. Tap to view a source; long-press a source chip to split it out (confirmed).
 * The novel twin of [reikai.presentation.manga.MergeSourceChips]; renders nothing for a single source.
 */
@Composable
fun NovelMergeSourceChips(
    sources: List<NovelMergeSourceInfo>,
    selectedSourceNovelId: Long?,
    onSelect: (Long?) -> Unit,
    onSplitSource: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.size <= 1) return
    var splitTarget by remember { mutableStateOf<NovelMergeSourceInfo?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedSourceNovelId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(MR.strings.merge_sources_all)) },
        )
        sources.forEach { source ->
            FilterChip(
                selected = selectedSourceNovelId == source.novelId,
                onClick = { onSelect(source.novelId) },
                label = { Text(source.sourceName) },
                // Long-press to split this source out (does not consume the tap, so selection works).
                modifier = Modifier.pointerInput(source.novelId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (awaitLongPressOrCancellation(down.id) != null) {
                            splitTarget = source
                        }
                    }
                },
            )
        }
    }

    splitTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { splitTarget = null },
            title = { Text(stringResource(MR.strings.merge_sources_split_action)) },
            text = { Text(stringResource(MR.strings.merge_sources_split_confirm, target.sourceName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSplitSource(target.novelId)
                        splitTarget = null
                    },
                ) {
                    Text(stringResource(MR.strings.merge_sources_split_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { splitTarget = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
