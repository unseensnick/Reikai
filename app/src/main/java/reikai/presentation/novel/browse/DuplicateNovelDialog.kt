package reikai.presentation.novel.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import reikai.data.coil.NovelCover
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelWithChapterCount
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.selectedBackground

/**
 * Novel twin of `DuplicateMangaDialog`: shown when a similarly-named novel is already in the library.
 * Tapping a card migrates that duplicate onto the novel being added (like manga) when [onMigrate] is set,
 * else it opens the duplicate; long-press opens it. "Add anyway" proceeds with the add. Trimmed vs the
 * manga dialog (no per-card text-measured height).
 *
 * Grouping mirrors the manga twin: same-group duplicates collapse into one card and the user picks which
 * ones the new copy belongs with, since duplicate matching is fuzzy enough to list a different series.
 */
@Composable
fun DuplicateNovelDialog(
    duplicates: List<NovelWithChapterCount>,
    sourceNames: Map<String, String>,
    sourceSites: Map<String, String?>,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onOpenNovel: (Novel) -> Unit,
    // Tapping a card migrates that duplicate onto the novel being added (mirrors manga). Null (browse /
    // global search, which have no novel-being-added) keeps the tap-opens-the-novel behaviour.
    onMigrate: ((Novel) -> Unit)? = null,
    // Group id per duplicate id. Duplicates sharing one collapse into a single card, so joining an
    // existing group is one pick. Ungrouped duplicates are absent from the map.
    groupIdByNovelId: Map<Long, Long> = emptyMap(),
    // Favorite the new copy and merge it with the picked duplicates. Null hides the row (the same-title
    // suggestion pref is off, or merging is), keeping just "add anyway".
    onAddToGroup: ((selectedIds: List<Long>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    val cards = remember(duplicates, groupIdByNovelId) { collapseToCards(duplicates, groupIdByNovelId) }
    var selectionMode by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<Long>() }
    var selectionAnchor by remember { mutableStateOf<Long?>(null) }

    fun toggleSelection(id: Long) {
        if (!selection.remove(id)) selection.add(id)
        selectionAnchor = id.takeIf { selection.isNotEmpty() }
    }

    /** Select every card between the last-toggled anchor and [id] (inclusive), in display order. */
    fun toggleRangeSelection(id: Long) {
        val ids = cards.map { it.representative.novel.id }
        val anchorIndex = selectionAnchor?.let(ids::indexOf) ?: -1
        val targetIndex = ids.indexOf(id)
        if (anchorIndex < 0 || targetIndex < 0) {
            if (id !in selection) selection.add(id)
        } else {
            val range = if (anchorIndex <= targetIndex) anchorIndex..targetIndex else targetIndex..anchorIndex
            range.forEach { ids[it].takeIf { candidate -> candidate !in selection }?.let(selection::add) }
        }
        selectionAnchor = id
        selectionMode = true
    }

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
            Row(
                modifier = Modifier
                    .then(horizontalPaddingModifier)
                    .padding(top = MaterialTheme.padding.small)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.possible_duplicates_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )

                // Selection toggle, mirroring the browse toolbar's bulk-select action.
                if (onAddToGroup != null) {
                    IconButton(
                        onClick = {
                            selectionMode = !selectionMode
                            if (!selectionMode) {
                                selection.clear()
                                selectionAnchor = null
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = stringResource(MR.strings.action_bulk_select),
                            tint = if (selectionMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                }
            }

            Text(
                text = stringResource(MR.strings.possible_duplicates_summary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.then(horizontalPaddingModifier),
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                contentPadding = horizontalPadding,
            ) {
                items(items = cards, key = { it.representative.novel.id }) { card ->
                    val cardId = card.representative.novel.id
                    val novel = card.representative.novel
                    DuplicateNovelCard(
                        duplicate = card.representative,
                        groupedSourceCount = card.memberIds.size,
                        sourceName = sourceNames[novel.source] ?: novel.source,
                        sourceSite = sourceSites[novel.source],
                        isSelected = cardId in selection,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(cardId)
                            } else {
                                onDismissRequest()
                                if (onMigrate != null) onMigrate(novel) else onOpenNovel(novel)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) {
                                toggleRangeSelection(cardId)
                            } else if (onMigrate != null) {
                                onDismissRequest()
                                onOpenNovel(novel)
                            }
                        },
                    )
                }
            }

            Column(modifier = horizontalPaddingModifier) {
                HorizontalDivider()

                // Explicit "add to existing group" (vs the "add anyway" below that keeps it separate).
                // It merges only the picked cards, so it stays disabled until something is picked.
                onAddToGroup?.let { addToGroup ->
                    val hasSelection = selection.isNotEmpty()
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.action_add_to_group),
                        subtitle = if (hasSelection) {
                            stringResource(MR.strings.action_add_to_group_selected, selection.size)
                        } else {
                            stringResource(MR.strings.action_add_to_group_hint)
                        },
                        icon = Icons.Outlined.LibraryAdd,
                        onPreferenceClick = if (hasSelection) {
                            {
                                onDismissRequest()
                                addToGroup(selection.toList())
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .alpha(if (hasSelection) 1f else DISABLED_ALPHA),
                    )
                }

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

/**
 * One card in the duplicate dialog. A merged group renders as a single card standing for every member
 * ([memberIds]), so picking it joins the whole group; an ungrouped duplicate stands for itself.
 */
private data class DuplicateCard(
    val representative: NovelWithChapterCount,
    val memberIds: List<Long>,
)

private fun collapseToCards(
    duplicates: List<NovelWithChapterCount>,
    groupIdByNovelId: Map<Long, Long>,
): List<DuplicateCard> = duplicates
    // Group and novel ids are separate spaces, so the flag keeps a group id from colliding with a novel id.
    .groupBy { duplicate ->
        groupIdByNovelId[duplicate.novel.id]?.let { true to it } ?: (false to duplicate.novel.id)
    }
    .map { (_, members) -> DuplicateCard(members.first(), members.map { it.novel.id }) }

@Composable
private fun DuplicateNovelCard(
    duplicate: NovelWithChapterCount,
    groupedSourceCount: Int,
    sourceName: String,
    sourceSite: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val novel = duplicate.novel
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .selectedBackground(isSelected)
            // The tint alone is invisible here (a full-bleed cover leaves almost no card background
            // showing), so a selected card also gets a ring. Mirrors the manga twin.
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = SelectedCardBorderWidth,
                        color = MaterialTheme.colorScheme.secondary,
                        shape = MaterialTheme.shapes.medium,
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(MaterialTheme.padding.small),
    ) {
        Box {
            MangaCover.Book(
                data = NovelCover(
                    url = novel.thumbnailUrl,
                    site = sourceSite,
                    isNovelFavorite = true,
                    lastModified = novel.coverLastModified,
                    novelId = novel.id,
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
            // This card stands for a whole merged group, so say how many sources it covers. It gets its
            // own group below the chapter count: beside it, the row overflows the card and clips.
            if (groupedSourceCount > 1) {
                BadgeGroup(
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.BottomStart),
                ) {
                    Badge(
                        color = MaterialTheme.colorScheme.tertiary,
                        textColor = MaterialTheme.colorScheme.onTertiary,
                        text = pluralStringResource(
                            MR.plurals.num_grouped_sources,
                            groupedSourceCount,
                            groupedSourceCount,
                        ),
                    )
                }
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
            text = stringResource(NovelStatusCode.toStringRes(novel.status)),
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

private fun Long.statusIcon() = when (toInt()) {
    NovelStatusCode.ONGOING -> Icons.Outlined.Schedule
    NovelStatusCode.COMPLETED -> Icons.Outlined.DoneAll
    NovelStatusCode.LICENSED -> Icons.Outlined.AttachMoney
    NovelStatusCode.PUBLISHING_FINISHED -> Icons.Outlined.Done
    NovelStatusCode.CANCELLED -> Icons.Outlined.Close
    NovelStatusCode.ON_HIATUS -> Icons.Outlined.Pause
    else -> Icons.Outlined.Block
}

private val SelectedCardBorderWidth = 2.dp
