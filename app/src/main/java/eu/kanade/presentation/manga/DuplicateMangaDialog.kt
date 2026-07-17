package eu.kanade.presentation.manga

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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Typography
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMaxOfOrNull
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun DuplicateMangaDialog(
    duplicates: List<MangaWithChapterCount>,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onOpenManga: (manga: Manga) -> Unit,
    onMigrate: (manga: Manga) -> Unit,
    // RK: group id per duplicate id. Duplicates sharing one collapse into a single card, so joining an
    // existing group is one pick. Ungrouped duplicates are absent from the map.
    groupIdByMangaId: Map<Long, Long> = emptyMap(),
    // RK: favorite the new copy and merge it with the picked duplicates. Null hides the row (the
    // same-title suggestion pref is off), keeping just "add anyway".
    onAddToGroup: ((selectedIds: List<Long>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    // RK: duplicate detection is fuzzy (title substring or a shared tracker), so the list can hold a
    // genuinely different series. Grouping is therefore an explicit pick over these cards, never a
    // merge of every match. Selection is ephemeral, so it lives here and not in any ScreenModel.
    val cards = remember(duplicates, groupIdByMangaId) { collapseToCards(duplicates, groupIdByMangaId) }
    var selectionMode by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<Long>() }
    var selectionAnchor by remember { mutableStateOf<Long?>(null) }

    fun toggleSelection(id: Long) {
        if (!selection.remove(id)) selection.add(id)
        selectionAnchor = id.takeIf { selection.isNotEmpty() }
    }

    /** Select every card between the last-toggled anchor and [id] (inclusive), in display order. */
    fun toggleRangeSelection(id: Long) {
        val ids = cards.map { it.representative.manga.id }
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

                // RK: selection toggle, mirroring the browse toolbar's bulk-select action.
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
                modifier = Modifier.height(getMaximumMangaCardHeight(cards.map { it.representative })),
                contentPadding = horizontalPadding,
            ) {
                items(
                    items = cards,
                    key = { it.representative.manga.id },
                ) { card ->
                    val cardId = card.representative.manga.id
                    DuplicateMangaListItem(
                        duplicate = card.representative,
                        groupedSourceCount = card.memberIds.size,
                        getSource = { sourceManager.getOrStub(card.representative.manga.source) },
                        isSelected = cardId in selection,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(cardId)
                            } else {
                                onDismissRequest()
                                onMigrate(card.representative.manga)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) {
                                toggleRangeSelection(cardId)
                            } else {
                                onOpenManga(card.representative.manga)
                            }
                        },
                    )
                }
            }

            Column(modifier = horizontalPaddingModifier) {
                HorizontalDivider()

                // RK: explicit "add to existing group" (vs the "add anyway" below that keeps it separate).
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
 * RK: one card in the duplicate dialog. A merged group renders as a single card standing for every
 * member ([memberIds]), so picking it joins the whole group; an ungrouped duplicate stands for itself.
 */
private data class DuplicateCard(
    val representative: MangaWithChapterCount,
    val memberIds: List<Long>,
)

private fun collapseToCards(
    duplicates: List<MangaWithChapterCount>,
    groupIdByMangaId: Map<Long, Long>,
): List<DuplicateCard> = duplicates
    // Group and manga ids are separate spaces, so the flag keeps a group id from colliding with a manga id.
    .groupBy { duplicate ->
        groupIdByMangaId[duplicate.manga.id]?.let { true to it } ?: (false to duplicate.manga.id)
    }
    .map { (_, members) -> DuplicateCard(members.first(), members.map { it.manga.id }) }

@Composable
private fun DuplicateMangaListItem(
    duplicate: MangaWithChapterCount,
    groupedSourceCount: Int,
    getSource: () -> Source,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val source = getSource()
    val manga = duplicate.manga
    Column(
        modifier = Modifier
            .width(MangaCardWidth)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .selectedBackground(isSelected)
            // RK: the tint alone is invisible here (a full-bleed cover leaves almost no card background
            // showing), so a selected card also gets a ring. The library grid's solid-fill selected look
            // is not reusable: GridItemSelectable / selectedOutline are internal to its components.
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
            .combinedClickable(
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(MaterialTheme.padding.small),
    ) {
        Box {
            MangaCover.Book(
                data = ImageRequest.Builder(LocalContext.current)
                    .data(manga)
                    .crossfade(true)
                    .build(),
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
            // RK: this card stands for a whole merged group, so say how many sources it covers. It gets
            // its own group below the chapter count: beside it, the row overflows the card and clips.
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
            text = manga.title,
            style = MaterialTheme.typography.titleSmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )

        if (!manga.author.isNullOrBlank()) {
            MangaDetailRow(
                text = manga.author!!,
                iconImageVector = Icons.Filled.PersonOutline,
                maxLines = 2,
            )
        }

        if (!manga.artist.isNullOrBlank() && manga.author != manga.artist) {
            MangaDetailRow(
                text = manga.artist!!,
                iconImageVector = Icons.Filled.Brush,
                maxLines = 2,
            )
        }

        MangaDetailRow(
            text = when (manga.status) {
                SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
                SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
                SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
                SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
                SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
                SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
                else -> stringResource(MR.strings.unknown)
            },
            iconImageVector = when (manga.status) {
                SManga.ONGOING.toLong() -> Icons.Outlined.Schedule
                SManga.COMPLETED.toLong() -> Icons.Outlined.DoneAll
                SManga.LICENSED.toLong() -> Icons.Outlined.AttachMoney
                SManga.PUBLISHING_FINISHED.toLong() -> Icons.Outlined.Done
                SManga.CANCELLED.toLong() -> Icons.Outlined.Close
                SManga.ON_HIATUS.toLong() -> Icons.Outlined.Pause
                else -> Icons.Outlined.Block
            },
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (source is StubSource) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = source.name,
                style = MaterialTheme.typography.labelSmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MangaDetailRow(
    text: String,
    iconImageVector: ImageVector,
    maxLines: Int = 1,
) {
    Row(
        modifier = Modifier
            .secondaryItemAlpha()
            .padding(top = MaterialTheme.padding.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconImageVector,
            contentDescription = null,
            modifier = Modifier.size(MangaDetailsIconWidth),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
        )
    }
}

@Composable
private fun getMaximumMangaCardHeight(duplicates: List<MangaWithChapterCount>): Dp {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val textMeasurer = rememberTextMeasurer()

    val smallPadding = with(density) { MaterialTheme.padding.small.roundToPx() }
    val extraSmallPadding = with(density) { MaterialTheme.padding.extraSmall.roundToPx() }

    val width = with(density) { MangaCardWidth.roundToPx() - (2 * smallPadding) }
    val iconWidth = with(density) { MangaDetailsIconWidth.roundToPx() }

    val coverHeight = width / MangaCover.Book.ratio
    val constraints = Constraints(maxWidth = width)
    val detailsConstraints = Constraints(maxWidth = width - iconWidth - extraSmallPadding)

    return remember(
        duplicates,
        density,
        typography,
        textMeasurer,
        smallPadding,
        extraSmallPadding,
        coverHeight,
        constraints,
        detailsConstraints,
    ) {
        duplicates.fastMaxOfOrNull {
            calculateMangaCardHeight(
                manga = it.manga,
                density = density,
                typography = typography,
                textMeasurer = textMeasurer,
                smallPadding = smallPadding,
                extraSmallPadding = extraSmallPadding,
                coverHeight = coverHeight,
                constraints = constraints,
                detailsConstraints = detailsConstraints,
            )
        }
            ?: 0.dp
    }
}

private fun calculateMangaCardHeight(
    manga: Manga,
    density: Density,
    typography: Typography,
    textMeasurer: TextMeasurer,
    smallPadding: Int,
    extraSmallPadding: Int,
    coverHeight: Float,
    constraints: Constraints,
    detailsConstraints: Constraints,
): Dp {
    val titleHeight = textMeasurer.measureHeight(manga.title, typography.titleSmall, 2, constraints)
    val authorHeight = if (!manga.author.isNullOrBlank()) {
        textMeasurer.measureHeight(manga.author!!, typography.bodySmall, 2, detailsConstraints)
    } else {
        0
    }
    val artistHeight = if (!manga.artist.isNullOrBlank() && manga.author != manga.artist) {
        textMeasurer.measureHeight(manga.artist!!, typography.bodySmall, 2, detailsConstraints)
    } else {
        0
    }
    val statusHeight = textMeasurer.measureHeight("", typography.bodySmall, 2, detailsConstraints)
    val sourceHeight = textMeasurer.measureHeight("", typography.labelSmall, 1, constraints)

    val totalHeight = coverHeight + titleHeight + authorHeight + artistHeight + statusHeight + sourceHeight
    return with(density) { ((2 * smallPadding) + totalHeight + (5 * extraSmallPadding)).toDp() }
}

private fun TextMeasurer.measureHeight(
    text: String,
    style: TextStyle,
    maxLines: Int,
    constraints: Constraints,
): Int = measure(
    text = text,
    style = style,
    overflow = TextOverflow.Ellipsis,
    maxLines = maxLines,
    constraints = constraints,
)
    .size
    .height

private val MangaCardWidth = 150.dp
private val MangaDetailsIconWidth = 16.dp
private val SelectedCardBorderWidth = 2.dp
