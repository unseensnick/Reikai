package reikai.presentation.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Pinned / All / Has-results filter chips shared by the manga and novel global search, with a
 * horizontal divider beneath the row. Driven by primitives ([isPinnedOnly] rather than either side's
 * `SourceFilter` enum) so the one row serves both. [showSourceFilter] hides Pinned / All (e.g. a
 * source-scoped search that only toggles results).
 */
@Composable
fun EntrySearchSourceFilterChips(
    isPinnedOnly: Boolean,
    onlyShowHasResults: Boolean,
    showSourceFilter: Boolean,
    onSelectPinnedOnly: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleResults: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            if (showSourceFilter) {
                FilterChip(
                    selected = isPinnedOnly,
                    onClick = onSelectPinnedOnly,
                    leadingIcon = {
                        Icon(Icons.Outlined.PushPin, null, Modifier.size(FilterChipDefaults.IconSize))
                    },
                    label = { Text(stringResource(MR.strings.pinned_sources)) },
                )
                FilterChip(
                    selected = !isPinnedOnly,
                    onClick = onSelectAll,
                    leadingIcon = {
                        Icon(Icons.Outlined.DoneAll, null, Modifier.size(FilterChipDefaults.IconSize))
                    },
                    label = { Text(stringResource(MR.strings.all)) },
                )
            }
            FilterChip(
                selected = onlyShowHasResults,
                onClick = onToggleResults,
                leadingIcon = {
                    Icon(Icons.Outlined.FilterList, null, Modifier.size(FilterChipDefaults.IconSize))
                },
                label = { Text(stringResource(MR.strings.has_results)) },
            )
        }
        HorizontalDivider()
    }
}
