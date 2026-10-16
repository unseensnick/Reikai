package eu.kanade.presentation.more.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.stats.components.StatsItem
import eu.kanade.presentation.more.stats.components.StatsOverviewItem
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.util.toDurationString
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Schedule
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.stats.StatsCard
import reikai.presentation.stats.StatsDimension
import reikai.presentation.stats.StatsShelf
import reikai.presentation.stats.StatsShelfContent
import reikai.presentation.stats.statsChartCards
import reikai.presentation.stats.statsGroupCards
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
    // RK --> All / Manga / Novels switch
    contentType: ContentType,
    onContentTypeSelected: (ContentType) -> Unit,
    onSeeAll: (StatsDimension) -> Unit,
    onOpenRow: (String) -> Unit,
    onOpenReading: () -> Unit,
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
        // Shelves of cards, side by side on a wide screen and one column on a phone
        item {
            StatsShelfContent(
                StatsShelf.GLANCE,
                listOf<StatsCard>(
                    { OverviewSection(state.overview, it) },
                    { TitlesStats(state.titles, it) },
                    { ChapterStats(state.chapters, it) },
                    { TrackerStats(state.trackers, it) },
                ),
            )
        }
        item { StatsShelfContent(StatsShelf.CHARTS, statsChartCards(state.charts, onSeeAll, onOpenReading)) }
        item { StatsShelfContent(StatsShelf.LISTS, statsGroupCards(state.charts, onSeeAll, onOpenRow)) }
        // RK <--
    }
}

@Composable
private fun OverviewSection( // RK: a shelf card, no list receiver
    data: StatsData.Overview,
    modifier: Modifier, // RK
) {
    val none = stringResource(MR.strings.none)
    val context = LocalContext.current
    val readDurationString = remember(data.totalReadDuration) {
        data.totalReadDuration
            .toDuration(DurationUnit.MILLISECONDS)
            .toDurationString(context, fallback = none)
    }
    // RK: a shelf card, centred when stretched
    SectionCard(MR.strings.label_overview_section, modifier, Arrangement.Center) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            StatsOverviewItem(
                title = data.libraryMangaCount.toString(),
                subtitle = stringResource(MR.strings.in_library),
                icon = MaterialSymbols.Rounded.CollectionsBookmark,
            )
            StatsOverviewItem(
                title = readDurationString,
                subtitle = stringResource(MR.strings.label_read_duration),
                icon = MaterialSymbols.Rounded.Schedule,
            )
            StatsOverviewItem(
                title = data.completedMangaCount.toString(),
                subtitle = stringResource(MR.strings.label_completed_titles),
                icon = MaterialSymbols.Rounded.LocalLibrary,
            )
        }
    }
}

@Composable
private fun TitlesStats( // RK: a shelf card, no list receiver
    data: StatsData.Titles,
    modifier: Modifier, // RK
) {
    // RK: a shelf card, centred when stretched
    SectionCard(MR.strings.label_titles_section, modifier, Arrangement.Center) {
        Row {
            StatsItem(
                data.globalUpdateItemCount.toString(),
                stringResource(MR.strings.label_titles_in_global_update),
            )
            StatsItem(
                data.startedMangaCount.toString(),
                stringResource(MR.strings.label_started),
            )
            StatsItem(
                data.localMangaCount.toString(),
                stringResource(MR.strings.label_local),
            )
        }
    }
}

@Composable
private fun ChapterStats( // RK: a shelf card, no list receiver
    data: StatsData.Chapters,
    modifier: Modifier, // RK
) {
    // RK: a shelf card, centred when stretched
    SectionCard(MR.strings.chapters, modifier, Arrangement.Center) {
        Row {
            StatsItem(
                data.totalChapterCount.toString(),
                stringResource(MR.strings.label_total_chapters),
            )
            StatsItem(
                data.readChapterCount.toString(),
                stringResource(MR.strings.label_read_chapters),
            )
            StatsItem(
                data.downloadCount.toString(),
                stringResource(MR.strings.label_downloaded),
            )
        }
    }
}

@Composable
private fun TrackerStats( // RK: a shelf card, no list receiver
    data: StatsData.Trackers,
    modifier: Modifier, // RK
) {
    val notApplicable = stringResource(MR.strings.not_applicable)
    val meanScoreStr = remember(data.trackedTitleCount, data.meanScore) {
        if (data.trackedTitleCount > 0 && !data.meanScore.isNaN()) {
            // All other numbers are localized in English
            "%.2f ★".format(Locale.ENGLISH, data.meanScore)
        } else {
            notApplicable
        }
    }
    // RK: a shelf card, centred when stretched
    SectionCard(MR.strings.label_tracker_section, modifier, Arrangement.Center) {
        Row {
            StatsItem(
                data.trackedTitleCount.toString(),
                stringResource(MR.strings.label_tracked_titles),
            )
            StatsItem(
                meanScoreStr,
                stringResource(MR.strings.label_mean_score),
            )
            StatsItem(
                data.trackerCount.toString(),
                stringResource(MR.strings.label_used),
            )
        }
    }
}
