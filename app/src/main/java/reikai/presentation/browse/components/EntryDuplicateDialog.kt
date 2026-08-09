package reikai.presentation.browse.components

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
import reikai.data.novel.NovelStatusCode
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
 * The "possible duplicates" dialog for both content types, shown when the entry being added looks
 * like one already in the library. Generic over [T] so each type keeps its own row for the
 * callbacks, while [toUi] maps that row to the neutral card. Matching is fuzzy (a title substring
 * or a shared tracker), so the list can hold a genuinely different series: grouping is an explicit
 * pick over these cards, never a merge of every match, and selection is ephemeral enough to live
 * here rather than in any ViewModel.
 */
@Composable
fun <T> EntryDuplicateDialog(
    duplicates: List<T>,
    toUi: (T) -> EntryDuplicateCardUi,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onOpen: (T) -> Unit,
    onMigrate: (T) -> Unit,
    // Group id per duplicate id. Duplicates sharing one collapse into a single card, so joining an
    // existing group is one pick. Ungrouped duplicates are absent from the map.
    groupIdByEntryId: Map<Long, Long> = emptyMap(),
    // Favorite the new copy and merge it with the picked duplicates. Null hides the row (the same-title
    // suggestion pref is off, or merging is), keeping just "add anyway".
    onAddToGroup: ((selectedIds: List<Long>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    val cards = remember(duplicates, groupIdByEntryId) {
        collapseToCards(duplicates, toUi, groupIdByEntryId)
    }
    var selectionMode by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<Long>() }
    var selectionAnchor by remember { mutableStateOf<Long?>(null) }

    fun toggleSelection(id: Long) {
        if (!selection.remove(id)) selection.add(id)
        selectionAnchor = id.takeIf { selection.isNotEmpty() }
    }

    /** Select every card between the last-toggled anchor and [id] (inclusive), in display order. */
    fun toggleRangeSelection(id: Long) {
        val ids = cards.map { it.ui.id }
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
                modifier = Modifier.height(getMaximumCardHeight(cards.map { it.ui })),
                contentPadding = horizontalPadding,
            ) {
                items(
                    items = cards,
                    key = { it.ui.id },
                ) { card ->
                    val cardId = card.ui.id
                    EntryDuplicateCard(
                        ui = card.ui,
                        groupedSourceCount = card.memberIds.size,
                        isSelected = cardId in selection,
                        onClick = {
                            if (selectionMode) {
                                toggleSelection(cardId)
                            } else {
                                onDismissRequest()
                                onMigrate(card.entry)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) {
                                toggleRangeSelection(cardId)
                            } else {
                                onOpen(card.entry)
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
 * One card, standing for a whole merged group when [memberIds] holds more than one entry, so picking
 * it joins the whole group. [entry] is the type's own row, kept for the callbacks.
 */
private data class DuplicateCard<T>(
    val entry: T,
    val ui: EntryDuplicateCardUi,
    val memberIds: List<Long>,
)

private fun <T> collapseToCards(
    duplicates: List<T>,
    toUi: (T) -> EntryDuplicateCardUi,
    groupIdByEntryId: Map<Long, Long>,
): List<DuplicateCard<T>> = duplicates
    .map { it to toUi(it) }
    // Group and entry ids are separate spaces, so the flag keeps a group id from colliding with an entry id.
    .groupBy { (_, ui) -> groupIdByEntryId[ui.id]?.let { true to it } ?: (false to ui.id) }
    .map { (_, members) ->
        val (entry, ui) = members.first()
        DuplicateCard(entry, ui, members.map { it.second.id })
    }

@Composable
private fun EntryDuplicateCard(
    ui: EntryDuplicateCardUi,
    groupedSourceCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(CardWidth)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .selectedBackground(isSelected)
            // The tint alone is invisible here (a full-bleed cover leaves almost no card background
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
                    .data(ui.coverModel)
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
                        ui.chapterCount.toInt(),
                        ui.chapterCount,
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
            text = ui.title,
            style = MaterialTheme.typography.titleSmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )

        ui.displayAuthor?.let {
            EntryDetailRow(text = it, iconImageVector = Icons.Filled.PersonOutline, maxLines = 2)
        }

        ui.displayArtist?.let {
            EntryDetailRow(text = it, iconImageVector = Icons.Filled.Brush, maxLines = 2)
        }

        EntryDetailRow(
            text = stringResource(NovelStatusCode.toStringRes(ui.status)),
            iconImageVector = statusIcon(ui.status),
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (ui.source is EntrySourceLabel.Missing) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(EntryDetailsIconWidth),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = ui.source.name,
                style = MaterialTheme.typography.labelSmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EntryDetailRow(
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
            modifier = Modifier.size(EntryDetailsIconWidth),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
        )
    }
}

/** SManga and NovelStatusCode share the same 0-6 codes, so one switch serves both content types. */
private fun statusIcon(status: Long): ImageVector = when (status.toInt()) {
    NovelStatusCode.ONGOING -> Icons.Outlined.Schedule
    NovelStatusCode.COMPLETED -> Icons.Outlined.DoneAll
    NovelStatusCode.LICENSED -> Icons.Outlined.AttachMoney
    NovelStatusCode.PUBLISHING_FINISHED -> Icons.Outlined.Done
    NovelStatusCode.CANCELLED -> Icons.Outlined.Close
    NovelStatusCode.ON_HIATUS -> Icons.Outlined.Pause
    else -> Icons.Outlined.Block
}

/** Blank author and an artist that only repeats the author are not worth a row of their own. */
private val EntryDuplicateCardUi.displayAuthor: String? get() = author?.takeUnless { it.isBlank() }
private val EntryDuplicateCardUi.displayArtist: String? get() = artist?.takeUnless { it.isBlank() || it == author }

/**
 * Every card in the row gets the tallest card's height, so the "add anyway" row below does not jump as
 * the user scrolls. Measured rather than laid out because a LazyRow sizes to its first item.
 */
@Composable
private fun getMaximumCardHeight(cards: List<EntryDuplicateCardUi>): Dp {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val textMeasurer = rememberTextMeasurer()

    val smallPadding = with(density) { MaterialTheme.padding.small.roundToPx() }
    val extraSmallPadding = with(density) { MaterialTheme.padding.extraSmall.roundToPx() }

    val width = with(density) { CardWidth.roundToPx() - (2 * smallPadding) }
    val iconWidth = with(density) { EntryDetailsIconWidth.roundToPx() }

    val coverHeight = width / MangaCover.Book.ratio
    val constraints = Constraints(maxWidth = width)
    val detailsConstraints = Constraints(maxWidth = width - iconWidth - extraSmallPadding)

    return remember(
        cards,
        density,
        typography,
        textMeasurer,
        smallPadding,
        extraSmallPadding,
        coverHeight,
        constraints,
        detailsConstraints,
    ) {
        cards.fastMaxOfOrNull {
            calculateCardHeight(
                card = it,
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

private fun calculateCardHeight(
    card: EntryDuplicateCardUi,
    density: Density,
    typography: Typography,
    textMeasurer: TextMeasurer,
    smallPadding: Int,
    extraSmallPadding: Int,
    coverHeight: Float,
    constraints: Constraints,
    detailsConstraints: Constraints,
): Dp {
    val titleHeight = textMeasurer.measureHeight(card.title, typography.titleSmall, 2, constraints)
    val authorHeight = card.displayAuthor
        ?.let { textMeasurer.measureHeight(it, typography.bodySmall, 2, detailsConstraints) }
        ?: 0
    val artistHeight = card.displayArtist
        ?.let { textMeasurer.measureHeight(it, typography.bodySmall, 2, detailsConstraints) }
        ?: 0
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

private val CardWidth = 150.dp
private val EntryDetailsIconWidth = 16.dp
private val SelectedCardBorderWidth = 2.dp
