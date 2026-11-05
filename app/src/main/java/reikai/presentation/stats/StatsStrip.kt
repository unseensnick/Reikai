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
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/** One figure of the overview strip: a number, what it counts, and an icon to find it by. */
data class StatsFigure(val icon: ImageVector, val value: String, val label: String)

/** The overview's figures, the four number cards' twelve, in their order. */
@Composable
fun statsOverviewFigures(
    overview: StatsData.Overview,
    titles: StatsData.Titles,
    chapters: StatsData.Chapters,
    trackers: StatsData.Trackers,
): List<StatsFigure> {
    val none = stringResource(MR.strings.none)
    val readTime = overview.totalReadDuration.toDuration(DurationUnit.MILLISECONDS)
        .toDurationString(LocalContext.current, fallback = none)
    val meanScore = if (trackers.trackedTitleCount > 0 && !trackers.meanScore.isNaN()) {
        "%.2f ★".format(Locale.ENGLISH, trackers.meanScore)
    } else {
        stringResource(MR.strings.not_applicable)
    }
    return with(MaterialSymbols.Rounded) {
        listOf(
            StatsFigure(
                CollectionsBookmark,
                overview.libraryMangaCount.toString(),
                stringResource(MR.strings.in_library),
            ),
            StatsFigure(Schedule, readTime, stringResource(MR.strings.label_read_duration)),
            StatsFigure(
                LocalLibrary,
                overview.completedMangaCount.toString(),
                stringResource(MR.strings.label_completed_titles),
            ),
            StatsFigure(
                Sync,
                titles.globalUpdateItemCount.toString(),
                stringResource(MR.strings.label_titles_in_global_update),
            ),
            StatsFigure(Bookmark, titles.startedMangaCount.toString(), stringResource(MR.strings.label_started)),
            StatsFigure(Folder, titles.localMangaCount.toString(), stringResource(MR.strings.label_local)),
            StatsFigure(Book, chapters.totalChapterCount.toString(), stringResource(MR.strings.label_total_chapters)),
            StatsFigure(DoneAll, chapters.readChapterCount.toString(), stringResource(MR.strings.label_read_chapters)),
            StatsFigure(Download, chapters.downloadCount.toString(), stringResource(MR.strings.label_downloaded)),
            StatsFigure(
                Verified,
                trackers.trackedTitleCount.toString(),
                stringResource(MR.strings.label_tracked_titles),
            ),
            StatsFigure(Favorite, meanScore, stringResource(MR.strings.label_mean_score)),
            StatsFigure(Public, trackers.trackerCount.toString(), stringResource(MR.strings.label_used)),
        )
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
