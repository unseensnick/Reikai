package reikai.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Sticky `All / Manga / Novels` switch shared by the Browse Sources/Extensions tabs and the
 * Library. The single label mapping lives here so callers only pass the selected type. Net-new
 * Reikai UI; mirrors the [reikai.presentation.components.MergeSourceChips] chip-row pattern.
 */
@Composable
fun ContentTypeFilterChips(
    selected: ContentType,
    onSelect: (ContentType) -> Unit,
    modifier: Modifier = Modifier,
    // Every current caller (library included, since the All chip shipped) uses all three.
    types: List<ContentType> = ContentType.entries,
    // Opt-in per-type count badge (the Extensions tab passes pending updates per type); empty = none.
    badges: Map<ContentType, Int> = emptyMap(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContentTypeChips(selected, onSelect, types, badges)
    }
}

/**
 * The same chips without a row of their own, for a caller that already has one. Global search puts
 * them beside its source filters, so one scrolling row holds every chip rather than stacking two.
 */
@Composable
fun RowScope.ContentTypeChips(
    selected: ContentType,
    onSelect: (ContentType) -> Unit,
    types: List<ContentType> = ContentType.entries,
    badges: Map<ContentType, Int> = emptyMap(),
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

internal val ContentType.labelRes
    get() = when (this) {
        ContentType.ALL -> MR.strings.content_type_all
        ContentType.MANGA -> MR.strings.content_type_manga
        ContentType.NOVELS -> MR.strings.content_type_novels
    }
