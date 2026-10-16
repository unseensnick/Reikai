package reikai.presentation.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A group of overview cards of one kind. [columnCounts] are the counts its cards divide into evenly, so four
 * number cards go two across and never three and one.
 */
enum class StatsShelf(val title: StringResource, private val minCardWidth: Dp, private val columnCounts: List<Int>) {
    GLANCE(MR.strings.stats_shelf_glance, 240.dp, listOf(1, 2)),
    CHARTS(MR.strings.stats_shelf_charts, 340.dp, listOf(1, 2)),
    LISTS(MR.strings.stats_shelf_lists, 340.dp, listOf(1, 2, 3)),
    ;

    fun columns(width: Dp): Int = columnCounts.last { it == 1 || width >= minCardWidth * it }
}

/**
 * A shelf's cards in rows, each card's body stretched to the tallest in its row so the row lines up. A phone
 * gets one column and no shelf title, the same as a plain list.
 */
@Composable
fun StatsShelfContent(shelf: StatsShelf, cards: List<StatsCard>) {
    if (cards.isEmpty()) return
    BoxWithConstraints {
        val columns = shelf.columns(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            if (columns > 1) {
                Text(
                    text = stringResource(shelf.title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(
                        start = MaterialTheme.padding.extraLarge,
                        top = MaterialTheme.padding.medium,
                    ),
                )
            }
            cards.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEach { card ->
                        Column(Modifier.weight(1f).fillMaxHeight()) { card(Modifier.weight(1f)) }
                    }
                    // A short last row keeps the column width rather than stretching its cards.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
