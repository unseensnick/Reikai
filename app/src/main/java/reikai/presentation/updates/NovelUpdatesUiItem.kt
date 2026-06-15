package reikai.presentation.updates

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

/**
 * One light-novel row in the Updates tab, the novel twin of the private `UpdatesUiItem`. Renders the
 * novel cover, title, the chapter name with an unread dot / bookmark / read-progress, and the download
 * indicator. The whole row opens the chapter (no separate cover-to-details tap in this first slice).
 */
@Composable
fun NovelUpdatesUiItem(
    item: NovelUpdatesItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadClick: ((ChapterDownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val update = item.update
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f
    val readProgress = (update.lastTextProgress / 100L).toInt()
        .takeIf { !update.read && it > 0 }
        ?.let { "$it%" }

    Row(
        modifier = modifier
            .selectedBackground(item.selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Square(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = update.coverData,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = update.novelTitle,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                var textHeight by remember { mutableIntStateOf(0) }
                if (!update.read) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textHeight = it.size.height },
                    modifier = Modifier.weight(weight = 1f, fill = false),
                )
                if (readProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ChapterDownloadIndicator(
            enabled = onDownloadClick != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = { item.downloadState },
            downloadProgressProvider = { 0 },
            onClick = { onDownloadClick?.invoke(it) },
        )
    }
}
