package reikai.presentation.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.util.toDurationString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/** One figure of the overview strip: a number, what it counts, and an icon to find it by. */
data class StatsFigure(val icon: ImageVector, val value: String, val label: String)

/** The overview's tiles for [figures], in that order. */
@Composable
fun statsOverviewFigures(
    overview: StatsData.Overview,
    titles: StatsData.Titles,
    chapters: StatsData.Chapters,
    trackers: StatsData.Trackers,
    figures: List<StatsFigureKind>,
): List<StatsFigure> {
    val none = stringResource(MR.strings.none)
    val readTime = overview.totalReadDuration.toDuration(DurationUnit.MILLISECONDS)
        .toDurationString(LocalContext.current, fallback = none)
    val meanScore = if (trackers.trackedTitleCount > 0 && !trackers.meanScore.isNaN()) {
        "%.2f ★".format(Locale.ENGLISH, trackers.meanScore)
    } else {
        stringResource(MR.strings.not_applicable)
    }
    return figures.map { kind ->
        val value = when (kind) {
            StatsFigureKind.IN_LIBRARY -> overview.libraryMangaCount.toString()
            StatsFigureKind.READ_DURATION -> readTime
            StatsFigureKind.COMPLETED -> overview.completedMangaCount.toString()
            StatsFigureKind.GLOBAL_UPDATE -> titles.globalUpdateItemCount.toString()
            StatsFigureKind.STARTED -> titles.startedMangaCount.toString()
            StatsFigureKind.LOCAL -> titles.localMangaCount.toString()
            StatsFigureKind.TOTAL_CHAPTERS -> chapters.totalChapterCount.toString()
            StatsFigureKind.READ_CHAPTERS -> chapters.readChapterCount.toString()
            StatsFigureKind.DOWNLOADED -> chapters.downloadCount.toString()
            StatsFigureKind.TRACKED_TITLES -> trackers.trackedTitleCount.toString()
            StatsFigureKind.MEAN_SCORE -> meanScore
            StatsFigureKind.TRACKERS_USED -> trackers.trackerCount.toString()
        }
        StatsFigure(kind.icon, value, stringResource(kind.label))
    }
}

/**
 * [figures] as icon tiles, two across on a phone and more as its shelf's width allows ([LocalStatsShelfWidth]).
 */
@Composable
fun StatsStrip(figures: List<StatsFigure>, modifier: Modifier = Modifier) {
    val width = LocalStatsShelfWidth.current
    val columns = when {
        width >= 840.dp -> 6
        width >= 560.dp -> 4
        else -> 2
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
        figures.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { StatsFigureTile(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatsFigureTile(figure: StatsFigure, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = figure.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.padding(start = MaterialTheme.padding.small)) {
            Text(text = figure.value, style = MaterialTheme.typography.titleMedium)
            Text(
                text = figure.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
