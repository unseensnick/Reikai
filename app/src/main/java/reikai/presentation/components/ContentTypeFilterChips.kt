package reikai.presentation.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import reikai.domain.library.ContentType
import reikai.domain.library.labelRes
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Sticky `All / Manga / Novels` switch shared by every content-type chip row, labelled through
 * [labelRes] so callers only pass the selected type. Net-new Reikai UI, built on the
 * [reikai.presentation.components.MergeSourceChips] chip-row pattern.
 */
@Composable
fun ContentTypeFilterChips(
    selected: ContentType,
    onSelect: (ContentType) -> Unit,
    modifier: Modifier = Modifier,
    types: List<ContentType> = ContentType.entries,
    // Opt-in per-type count badge (the Extensions tab passes pending updates per type); empty = none.
    badges: Map<ContentType, Int> = emptyMap(),
) {
    if (LocalShownContent.current != ContentType.ALL) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        types.forEach { type ->
            val count = badges[type] ?: 0
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(type.labelRes)) },
                trailingIcon = if (count > 0) {
                    { Badge { Text(text = "$count") } }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The scroll position of the list under a [ContentTypeFilterChips] row, which starts at the top whenever
 * [selected] changes. Left to itself a keyed list keeps the old first row in view, so a section the new
 * chip adds above it (the Extensions tab's pending updates) opens scrolled off the top.
 */
@Composable
fun rememberChipListState(selected: ContentType): LazyListState =
    rememberSaveable(selected, saver = LazyListState.Saver) { LazyListState() }

/** [rememberChipListState] for a plain scrolling column under the chip row. */
@Composable
fun rememberChipScrollState(selected: ContentType): ScrollState =
    rememberSaveable(selected, saver = ScrollState.Saver) { ScrollState(0) }
