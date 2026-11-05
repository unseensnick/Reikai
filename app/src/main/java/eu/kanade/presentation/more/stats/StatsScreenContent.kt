package eu.kanade.presentation.more.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.stats.StatsCard
import reikai.presentation.stats.StatsCardKind
import reikai.presentation.stats.StatsDimension
import reikai.presentation.stats.StatsGroupKey
import reikai.presentation.stats.StatsRow
import reikai.presentation.stats.StatsShelfContent
import reikai.presentation.stats.StatsStrip
import reikai.presentation.stats.statsChartCards
import reikai.presentation.stats.statsGroupCards
import reikai.presentation.stats.statsOverviewFigures
import reikai.presentation.stats.statsReadingCard
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
    // RK --> All / Manga / Novels switch
    contentType: ContentType,
    onContentTypeSelected: (ContentType) -> Unit,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenRow: (StatsRow) -> Unit,
    onOpenKey: (StatsGroupKey) -> Unit,
    onOpenEntry: (EntryId) -> Unit,
    onOpenReading: () -> Unit,
    onOpenMostRead: () -> Unit,
    cardLayout: List<StatsCardKind>,
    // RK <--
) {
    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        // RK --> content-type switch, mirroring the Updates/History tabs
        item {
            ContentTypeFilterChips(
                selected = contentType,
                onSelect = onContentTypeSelected,
            )
        }
        // Shelves of cards in the user's order, side by side on a wide screen and one column on a phone
        val cards: Map<StatsCardKind, StatsCard> = buildMap {
            put(StatsCardKind.OVERVIEW) { fill ->
                val figures = statsOverviewFigures(state.overview, state.titles, state.chapters, state.trackers)
                SectionCard(modifier = fill) { StatsStrip(figures) }
            }
            put(StatsCardKind.READING, statsReadingCard(state.charts, onOpenReading))
            putAll(statsChartCards(state.charts, onSeeAll, onOpenKey))
            putAll(statsGroupCards(state.charts, onSeeAll, onOpenRow, onOpenEntry, onOpenMostRead))
        }
        cardLayout.groupBy { it.shelf }.forEach { (shelf, kinds) ->
            item(key = shelf) { StatsShelfContent(shelf, kinds.mapNotNull(cards::get)) }
        }
        // RK <--
    }
}
