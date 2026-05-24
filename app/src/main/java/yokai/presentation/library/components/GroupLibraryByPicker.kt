package yokai.presentation.library.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource as androidStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import yokai.i18n.MR
import android.R as AR

/**
 * Custom picker for `preferences.groupLibraryBy()`. Mirrors the legacy filter-sheet
 * "Group library by" dialog: each option row renders its grouping's drawable icon (via
 * [LibraryGroup.groupTypeDrawableRes]) alongside the label, and the trigger row at the top of
 * the Categories tab shows the currently selected grouping's icon as its leading icon so the
 * choice is visible at a glance.
 *
 * The generic ListPreferenceWidget only exposes a single leading ImageVector slot and does not
 * render per-entry icons, so this picker is its own widget. It cannot reuse the internal
 * BasePreferenceWidget either since that helper is package-private; the trigger row is
 * therefore composed inline using the same horizontal padding, typography, and click semantics.
 */
@Composable
fun GroupLibraryByPicker(
    selected: Int,
    entries: Map<Int, String>,
    onSelect: (Int) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val title = stringResource(MR.strings.group_library_by)
    val subtitle = entries[selected]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { dialogOpen = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = LibraryGroup.groupTypeDrawableRes(selected)),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(text = title) },
            text = {
                Box {
                    val state = rememberLazyListState()
                    LazyColumn(state = state) {
                        entries.forEach { (groupKey, groupLabel) ->
                            item {
                                GroupRow(
                                    label = groupLabel,
                                    iconRes = LibraryGroup.groupTypeDrawableRes(groupKey),
                                    isSelected = groupKey == selected,
                                    onSelected = {
                                        onSelect(groupKey)
                                        dialogOpen = false
                                    },
                                )
                            }
                        }
                    }
                    if (state.canScrollBackward) {
                        HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                    }
                    if (state.canScrollForward) {
                        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(text = androidStringResource(AR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GroupRow(
    label: String,
    @DrawableRes iconRes: Int,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = isSelected,
                onClick = { if (!isSelected) onSelected() },
            )
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            // Selected row picks up the radio's selected color via the system; the icon
            // tints with primary so the chosen grouping reads as active, matching the legacy
            // dialog where the highlighted row's icon shifts color.
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = isSelected,
            onClick = null,
        )
    }
}
