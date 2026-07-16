package reikai.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Collapsible category header for Reikai's single-list library. A chevron shows the collapse state
 * (tap the row to toggle). Real (non-dynamic) categories also get a per-category sort indicator and
 * a refresh button; in selection mode the chevron becomes a select-all circle for the category.
 */
@Composable
fun ReikaiLibraryCategoryHeader(
    name: String,
    itemCount: Int,
    showItemCount: Boolean,
    isCollapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Selection mode: the leading chevron becomes a select-all circle for this category.
    selectionMode: Boolean = false,
    allSelected: Boolean = false,
    onToggleSelectAll: () -> Unit = {},
    // Per-category affordances; null = hidden (e.g. dynamic groups, which have no real category).
    // The caller passes the EFFECTIVE sort (a category's override, or the global sort it follows): the
    // label via [sortLabel] and the arrow via [sortAscending], both content-type-decoded upstream so the
    // header never touches the raw flag bits (manga and novel encode different enums into them).
    sortLabel: StringResource? = null,
    sortAscending: Boolean? = null,
    onClickSort: (() -> Unit)? = null,
    onClickRefresh: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Row tap toggles collapse, but not while selecting (the circle handles taps then).
            .then(if (selectionMode) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    imageVector = if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = stringResource(MR.strings.action_select_all),
                )
            }
        } else {
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (showItemCount) "$name ($itemCount)" else name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (sortLabel != null && sortAscending != null && onClickSort != null) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onClickSort)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(sortLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = stringResource(MR.strings.action_sort),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (onClickRefresh != null) {
            IconButton(onClick = onClickRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(MR.strings.action_update_category),
                )
            }
        }
    }
}

/** The display label for a manga sort mode, reusing Mihon's Sort-tab strings. */
internal fun sortLabelRes(type: LibrarySort.Type): StringResource = when (type) {
    LibrarySort.Type.Alphabetical -> MR.strings.action_sort_alpha
    LibrarySort.Type.TotalChapters -> MR.strings.action_sort_total
    LibrarySort.Type.LastRead -> MR.strings.action_sort_last_read
    LibrarySort.Type.LastUpdate -> MR.strings.action_sort_last_manga_update
    LibrarySort.Type.UnreadCount -> MR.strings.action_sort_unread_count
    LibrarySort.Type.LatestChapter -> MR.strings.action_sort_latest_chapter
    LibrarySort.Type.ChapterFetchDate -> MR.strings.action_sort_chapter_fetch_date
    LibrarySort.Type.DateAdded -> MR.strings.action_sort_date_added
    LibrarySort.Type.TrackerMean -> MR.strings.action_sort_tracker_score
    LibrarySort.Type.Downloaded -> MR.strings.action_sort_downloaded
    LibrarySort.Type.Random -> MR.strings.action_sort_random
}
