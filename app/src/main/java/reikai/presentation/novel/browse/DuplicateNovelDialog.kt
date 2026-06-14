package reikai.presentation.novel.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelWithChapterCount
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * Novel twin of `DuplicateMangaDialog`: shown when a similarly-named novel is already in the library.
 * Each card opens the existing novel on tap (novels have no migration, so there is no migrate action);
 * "Add anyway" proceeds with the add. Trimmed vs the manga dialog (no per-card text-measured height).
 */
@Composable
fun DuplicateNovelDialog(
    duplicates: List<NovelWithChapterCount>,
    sourceNames: Map<String, String>,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onOpenNovel: (Novel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    AdaptiveSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            Text(
                text = stringResource(MR.strings.possible_duplicates_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .then(horizontalPaddingModifier)
                    .padding(top = MaterialTheme.padding.small),
            )

            Text(
                text = stringResource(MR.strings.possible_duplicates_summary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.then(horizontalPaddingModifier),
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                contentPadding = horizontalPadding,
            ) {
                items(items = duplicates, key = { it.novel.id }) { duplicate ->
                    DuplicateNovelCard(
                        duplicate = duplicate,
                        sourceName = sourceNames[duplicate.novel.source] ?: duplicate.novel.source,
                        onClick = {
                            onDismissRequest()
                            onOpenNovel(duplicate.novel)
                        },
                    )
                }
            }

            Column(modifier = horizontalPaddingModifier) {
                HorizontalDivider()

                TextPreferenceWidget(
                    title = stringResource(MR.strings.action_add_anyway),
                    icon = Icons.Outlined.Add,
                    onPreferenceClick = {
                        onDismissRequest()
                        onConfirm()
                    },
                    modifier = Modifier.clip(CircleShape),
                )
            }

            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .then(horizontalPaddingModifier)
                    .padding(bottom = MaterialTheme.padding.medium)
                    .heightIn(min = minHeight)
                    .fillMaxWidth(),
            ) {
                Text(
                    modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall),
                    text = stringResource(MR.strings.action_cancel),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun DuplicateNovelCard(
    duplicate: NovelWithChapterCount,
    sourceName: String,
    onClick: () -> Unit,
) {
    val novel = duplicate.novel
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(MaterialTheme.padding.small),
    ) {
        Box {
            MangaCover.Book(
                data = MangaCoverData(
                    mangaId = 0L,
                    sourceId = 0L,
                    isMangaFavorite = true,
                    url = novel.thumbnailUrl.orEmpty(),
                    lastModified = 0L,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            BadgeGroup(
                modifier = Modifier
                    .padding(4.dp)
                    .align(Alignment.TopStart),
            ) {
                Badge(
                    color = MaterialTheme.colorScheme.secondary,
                    textColor = MaterialTheme.colorScheme.onSecondary,
                    text = pluralStringResource(
                        MR.plurals.manga_num_chapters,
                        duplicate.chapterCount.toInt(),
                        duplicate.chapterCount,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.padding.extraSmall))

        Text(
            text = novel.title,
            style = MaterialTheme.typography.titleSmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )

        if (!novel.author.isNullOrBlank()) {
            NovelDetailRow(text = novel.author, iconImageVector = Icons.Filled.PersonOutline, maxLines = 2)
        }

        NovelDetailRow(
            text = stringResource(novel.status.statusStringRes()),
            iconImageVector = novel.status.statusIcon(),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.padding.extraSmall))

        Text(
            text = sourceName,
            style = MaterialTheme.typography.labelSmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
private fun NovelDetailRow(text: String, iconImageVector: ImageVector, maxLines: Int = 1) {
    Row(
        modifier = Modifier
            .secondaryItemAlpha()
            .padding(top = MaterialTheme.padding.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = iconImageVector, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
        )
    }
}

private fun Long.statusStringRes() = when (toInt()) {
    NovelStatusCode.ONGOING -> MR.strings.ongoing
    NovelStatusCode.COMPLETED -> MR.strings.completed
    NovelStatusCode.LICENSED -> MR.strings.licensed
    NovelStatusCode.PUBLISHING_FINISHED -> MR.strings.publishing_finished
    NovelStatusCode.CANCELLED -> MR.strings.cancelled
    NovelStatusCode.ON_HIATUS -> MR.strings.on_hiatus
    else -> MR.strings.unknown
}

private fun Long.statusIcon() = when (toInt()) {
    NovelStatusCode.ONGOING -> Icons.Outlined.Schedule
    NovelStatusCode.COMPLETED -> Icons.Outlined.DoneAll
    NovelStatusCode.LICENSED -> Icons.Outlined.AttachMoney
    NovelStatusCode.PUBLISHING_FINISHED -> Icons.Outlined.Done
    NovelStatusCode.CANCELLED -> Icons.Outlined.Close
    NovelStatusCode.ON_HIATUS -> Icons.Outlined.Pause
    else -> Icons.Outlined.Block
}
