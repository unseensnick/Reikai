package reikai.presentation.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import reikai.presentation.components.readableColumn
import reikai.presentation.components.rememberSettledReorder
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Every Statistics card under its shelf. A shelf drags as a block and folds with its arrow; a card drags
 * only within its shelf. A card's row shows or hides it, a shelf's row all of its cards. Each change hands
 * the whole new [layout] to [onArrange], or the whole new [hidden] set to [onHide].
 */
@Composable
fun StatsArrangeList(
    layout: List<StatsCardKind>,
    hidden: Set<StatsCardKind>,
    contentPadding: PaddingValues,
    onArrange: (List<StatsCardKind>) -> Unit,
    onHide: (Set<StatsCardKind>) -> Unit,
) {
    val listState = rememberLazyListState()
    // Held here while a drag runs, so the list moves under the finger without a write per step.
    val shelves = remember(layout) { layout.map { it.shelf }.distinct().toMutableStateList() }
    var collapsed by rememberSaveable { mutableStateOf(setOf<StatsShelf>()) }
    var drawnLayout by remember { mutableStateOf(layout) }
    var settledByDrag by remember { mutableStateOf(false) }
    fun arrange(cards: List<StatsCardKind>) {
        settledByDrag = true
        onArrange(cards)
    }
    val reorderState = rememberSettledReorder(shelves, listState, { it }, contentPadding) { settled ->
        arrange(layout.withShelves(settled))
    }
    // A drag ends where the finger left it. Reset keeps the old first row in view, which can leave the
    // shelves it moved above it off screen, so the list goes back to the top.
    LaunchedEffect(layout) {
        if (layout == drawnLayout) return@LaunchedEffect
        drawnLayout = layout
        if (settledByDrag) settledByDrag = false else listState.scrollToItem(0)
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        item(key = "hint") {
            Text(
                text = stringResource(MR.strings.stats_arrange_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .readableColumn()
                    .padding(horizontal = MaterialTheme.padding.extraLarge, vertical = MaterialTheme.padding.small),
            )
        }
        items(shelves.size, key = { shelves[it] }) { index ->
            val shelf = shelves[index]
            ReorderableItem(reorderState, shelf) { isDragging ->
                ShelfCard(
                    shelf = shelf,
                    cards = layout.filter { it.shelf == shelf },
                    hidden = hidden,
                    isDragging = isDragging,
                    isCollapsed = shelf in collapsed,
                    onCollapse = { collapsed = if (shelf in collapsed) collapsed - shelf else collapsed + shelf },
                    onArrange = { arrange(layout.withShelfCards(shelf, it)) },
                    onHide = onHide,
                    handle = Modifier.draggableHandle(),
                )
            }
        }
    }
}

@Composable
private fun ShelfCard(
    shelf: StatsShelf,
    cards: List<StatsCardKind>,
    hidden: Set<StatsCardKind>,
    isDragging: Boolean,
    isCollapsed: Boolean,
    onCollapse: () -> Unit,
    onArrange: (List<StatsCardKind>) -> Unit,
    onHide: (Set<StatsCardKind>) -> Unit,
    handle: Modifier,
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp, label = "shelfLift")
    val chevron by animateFloatAsState(if (isCollapsed) 0f else 180f, label = "shelfChevron")
    val shownCount = cards.count { it !in hidden }
    val toggleShelf = { onHide(if (shownCount < cards.size) hidden - cards.toSet() else hidden + cards) }
    // The reorder state keeps its first onSettle while this shelf's cards are unchanged, and onArrange
    // closes over the whole layout, so a drop must reach the latest one or it undoes other shelves.
    val currentOnArrange by rememberUpdatedState(onArrange)

    ElevatedCard(
        modifier = Modifier
            .readableColumn()
            .padding(horizontal = MaterialTheme.padding.medium),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = toggleShelf)
                .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = when (shownCount) {
                    cards.size -> ToggleableState.On
                    0 -> ToggleableState.Off
                    else -> ToggleableState.Indeterminate
                },
                onClick = toggleShelf,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.small),
            ) {
                Text(text = stringResource(shelf.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(MR.strings.stats_cards_shown, shownCount, cards.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(MR.strings.hopper_action_expand_collapse),
                    modifier = Modifier.rotate(chevron),
                )
            }
            DragHandle(handle)
        }
        AnimatedVisibility(visible = !isCollapsed) {
            Column(modifier = Modifier.padding(bottom = MaterialTheme.padding.small)) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ReorderableColumn(
                    list = cards,
                    onSettle = { from, to ->
                        currentOnArrange(cards.toMutableList().apply { add(to, removeAt(from)) })
                    },
                    modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                ) { _, card, isCardDragging ->
                    key(card) {
                        ReorderableItem {
                            CardRow(
                                card = card,
                                isShown = card !in hidden,
                                isDragging = isCardDragging,
                                onToggle = { onHide(if (card in hidden) hidden - card else hidden + card) },
                                handle = Modifier.draggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardRow(
    card: StatsCardKind,
    isShown: Boolean,
    isDragging: Boolean,
    onToggle: () -> Unit,
    handle: Modifier,
) {
    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "cardLift")
    Surface(
        shape = MaterialTheme.shapes.large,
        shadowElevation = elevation,
        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.extraSmall),
    ) {
        // The checkbox and handle sit under the shelf's own, so each pair reads as one column.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = MaterialTheme.padding.small - MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isShown, onCheckedChange = { onToggle() })
            Text(
                text = stringResource(card.title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.run { if (isShown) onSurface else onSurfaceVariant },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.small),
            )
            DragHandle(handle)
        }
    }
}

/** The drag handle in a full touch target, so it is as easy to grab as the buttons beside it. */
@Composable
private fun DragHandle(modifier: Modifier) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = MaterialSymbols.Rounded.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
