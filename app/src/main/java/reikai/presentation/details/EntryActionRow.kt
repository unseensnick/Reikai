package reikai.presentation.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Done
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.HourglassEmpty
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.roundedfilled.Favorite
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Shared details action row for manga and novels (favorite / fetch-interval / tracking / web view /
 * share). Replaces MangaActionRow + NovelActionRow so the two content types can't drift. Per-type
 * buttons gate on their inputs: the fetch-interval button shows only when [showIntervalButton] (manga),
 * and share only when [onShareClicked] is set (novels; manga shares from the toolbar). Long-pressing
 * favorite opens the category picker when [onEditCategory] is set.
 */
@Composable
fun EntryActionRow(
    favorite: Boolean,
    trackingCount: Int,
    onAddToLibraryClicked: () -> Unit,
    onTrackingClicked: () -> Unit,
    onEditCategory: (() -> Unit)?,
    showIntervalButton: Boolean,
    nextUpdate: Instant?,
    isUserIntervalMode: Boolean,
    onEditIntervalClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

    val nextUpdateDays = remember(nextUpdate) {
        if (nextUpdate != null) {
            Clock.System.now().daysUntil(nextUpdate, TimeZone.currentSystemDefault()).coerceAtLeast(0)
        } else {
            null
        }
    }

    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        EntryActionButton(
            title = if (favorite) {
                stringResource(MR.strings.in_library)
            } else {
                stringResource(MR.strings.add_to_library)
            },
            icon = if (favorite) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite,
            color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategory,
        )
        if (showIntervalButton) {
            EntryActionButton(
                title = when (nextUpdateDays) {
                    null -> stringResource(MR.strings.not_applicable)
                    0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                    else -> pluralStringResource(
                        MR.plurals.day,
                        count = nextUpdateDays,
                        nextUpdateDays,
                    )
                },
                icon = MaterialSymbols.Rounded.HourglassEmpty,
                color = if (isUserIntervalMode) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
                onClick = { onEditIntervalClicked?.invoke() },
            )
        }
        EntryActionButton(
            title = if (trackingCount == 0) {
                stringResource(MR.strings.manga_tracking_tab)
            } else {
                pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
            },
            icon = if (trackingCount == 0) MaterialSymbols.Rounded.Sync else MaterialSymbols.Rounded.Done,
            color = if (trackingCount == 0) defaultActionButtonColor else MaterialTheme.colorScheme.primary,
            onClick = onTrackingClicked,
        )
        if (onWebViewClicked != null) {
            EntryActionButton(
                title = stringResource(MR.strings.action_web_view),
                icon = MaterialSymbols.Rounded.Public,
                color = defaultActionButtonColor,
                onClick = onWebViewClicked,
                onLongClick = onWebViewLongClicked,
            )
        }
        if (onShareClicked != null) {
            EntryActionButton(
                title = stringResource(MR.strings.action_share),
                icon = MaterialSymbols.Rounded.Share,
                color = defaultActionButtonColor,
                onClick = onShareClicked,
            )
        }
    }
}

@Composable
private fun RowScope.EntryActionButton(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        onLongClick = onLongClick,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                color = color,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
