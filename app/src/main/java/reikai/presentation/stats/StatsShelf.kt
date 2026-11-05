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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
    GLANCE(MR.strings.stats_shelf_glance, 240.dp, listOf(1)),
    CHARTS(MR.strings.stats_shelf_charts, 320.dp, listOf(1, 2)),
    LISTS(MR.strings.stats_shelf_lists, 320.dp, listOf(1, 2, 3)),
    ;

    fun columns(width: Dp): Int = columnCounts.last { it == 1 || width >= minCardWidth * it }

    /** Wide enough for two of its cards, which is when the screen shows shelf titles, one card across or not. */
    fun isTitled(width: Dp): Boolean = width >= minCardWidth * 2
}

/**
 * The width of the shelf a card sits in, which a card reads to fit its content: it cannot measure itself,
 * since a shelf sizes its rows by intrinsic height and BoxWithConstraints cannot answer that. The screen's
 * width is not a stand-in, as the navigation rail takes part of it when Statistics is a tab. 0 outside a shelf.
 */
val LocalStatsShelfWidth = compositionLocalOf { 0.dp }

/**
 * A shelf's cards in rows, each card's body stretched to the tallest in its row so the row lines up. A phone
 * gets one column and no shelf title, the same as a plain list.
 */
@Composable
fun StatsShelfContent(shelf: StatsShelf, cards: List<StatsCard>) {
    if (cards.isEmpty()) return
    BoxWithConstraints {
        val columns = shelf.columns(maxWidth)
        val isTitled = shelf.isTitled(maxWidth)
        CompositionLocalProvider(LocalStatsShelfWidth provides maxWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                if (isTitled) {
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
}
