package reikai.presentation.manga

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel.MergeSourceInfo
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Switcher chips for a merged manga group: an "All" chip for the unified chapter list plus one chip
 * per grouped source. Net-new Reikai UI; renders nothing for a non-merged manga.
 */
@Composable
fun MergeSourceChips(
    sources: List<MergeSourceInfo>,
    selectedSourceMangaId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.size <= 1) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedSourceMangaId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(MR.strings.merge_sources_all)) },
        )
        sources.forEach { source ->
            FilterChip(
                selected = selectedSourceMangaId == source.mangaId,
                onClick = { onSelect(source.mangaId) },
                label = { Text(source.sourceName) },
            )
        }
    }
}
