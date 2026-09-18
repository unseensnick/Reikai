package reikai.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import reikai.domain.reader.ChapterTitleFormat
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

/*
 * The manga half of the reader's settings sheet: Mihon's reading mode, general and colour filter pages
 * (deleted, see docs/dev/off-path-manifest.md), with every control kept and regrouped by what it does.
 * The viewer-specific rows are read off the viewer that is running, not the stored reading mode:
 * auto-webtoon opens a DEFAULT series as long strip without writing the preference.
 */

/** Which of Mihon's viewers is showing, so each tab offers only the rows that viewer honours. */
private sealed interface RunningViewer {
    data object Pager : RunningViewer
    data object Webtoon : RunningViewer
    data class WebGpu(val viewer: WebGpuViewer) : RunningViewer
}

private fun Viewer?.running(): RunningViewer = when (this) {
    is WebtoonViewer -> RunningViewer.Webtoon
    is WebGpuViewer -> RunningViewer.WebGpu(this)
    else -> RunningViewer.Pager
}

private val readerThemes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

/** This series' reading mode and rotation, then how the running viewer lays a page out. */
@Composable
internal fun ColumnScope.MangaReadingPage(viewModel: ReaderSettingsViewModel) {
    val preferences = viewModel.preferences
    val manga by viewModel.mangaFlow.collectAsState()
    val viewer by viewModel.viewerFlow.collectAsState()

    HeadingItem(MR.strings.pref_category_for_this_series)
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    SettingsChipRow(MR.strings.pref_category_reading_mode) {
        ReadingMode.entries.map {
            FilterChip(
                selected = it == readingMode,
                onClick = { viewModel.onChangeReadingMode(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }
    EntryRotationRow(manga?.readerOrientation?.toInt(), viewModel.onChangeOrientation)

    when (val running = viewer.running()) {
        RunningViewer.Pager -> {
            HeadingItem(MR.strings.pager_viewer)
            ImageScaleTypeRow(preferences, ReaderPreferences.ImageScaleType)
            ZoomStartRow(preferences)
            CheckboxItem(label = stringResource(MR.strings.pref_crop_borders), pref = preferences.cropBorders)
            CheckboxItem(label = stringResource(MR.strings.pref_landscape_zoom), pref = preferences.landscapeZoom)
            DualPageRows(
                split = preferences.dualPageSplitPaged,
                invert = preferences.dualPageInvertPaged,
                rotate = preferences.dualPageRotateToFit,
                rotateInvert = preferences.dualPageRotateToFitInvert,
            )
        }
        RunningViewer.Webtoon -> {
            HeadingItem(MR.strings.webtoon_viewer)
            val numberFormat = remember { NumberFormat.getPercentInstance() }
            val webtoonSidePadding by preferences.webtoonSidePadding.collectAsState()
            SliderItem(
                value = webtoonSidePadding,
                valueRange = ReaderPreferences.let { it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX },
                label = stringResource(MR.strings.pref_webtoon_side_padding),
                valueString = numberFormat.format(webtoonSidePadding / 100f),
                onChange = { preferences.webtoonSidePadding.set(it) },
                pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            CheckboxItem(label = stringResource(MR.strings.pref_crop_borders), pref = preferences.cropBordersWebtoon)
            DualPageRows(
                split = preferences.dualPageSplitWebtoon,
                invert = preferences.dualPageInvertWebtoon,
                rotate = preferences.dualPageRotateToFitWebtoon,
                rotateInvert = preferences.dualPageRotateToFitInvertWebtoon,
            )
        }
        is RunningViewer.WebGpu -> {
            HeadingItem(MR.strings.webgpu_viewer)
            val webGpu = running.viewer
            if (!webGpu.isContinuous && !webGpu.isVertical) {
                val dualPageView by preferences.dualPageView.collectAsState()
                SettingsChipRow(MR.strings.pref_dual_page_view) {
                    ReaderPreferences.DualPageView.entries.map {
                        FilterChip(
                            selected = it == dualPageView,
                            onClick = { preferences.dualPageView.set(it) },
                            label = { Text(stringResource(it.titleRes)) },
                        )
                    }
                }
            }
            if (webGpu.isContinuous) {
                val numberFormat = remember { NumberFormat.getPercentInstance() }
                val continuousMinWidth by preferences.continuousMinWidth.collectAsState()
                SliderItem(
                    value = continuousMinWidth,
                    valueRange = 1..100,
                    label = stringResource(MR.strings.pref_continuous_minwidth),
                    valueString = numberFormat.format(continuousMinWidth / 100f),
                    onChange = { preferences.continuousMinWidth.set(it) },
                    pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                // Upstream offers the gap on Continuous vertical only, and useGap is how the viewer carries
                // that distinction, since one class serves it and long strip both.
                if ((webGpu as? WebGpuViewerContinuous)?.useGap == true) {
                    val continuousGap by preferences.continuousGap.collectAsState()
                    SliderItem(
                        value = continuousGap,
                        valueRange = 1..100,
                        label = stringResource(MR.strings.pref_continuous_gap),
                        valueString = numberFormat.format(continuousGap / 100f),
                        onChange = { preferences.continuousGap.set(it) },
                        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            } else if (!webGpu.isDualPageMode()) {
                ImageScaleTypeRow(preferences, ReaderPreferences.ImageScaleTypeWebGpuViewer)
                ZoomStartRow(preferences)
                CheckboxItem(label = stringResource(MR.strings.pref_crop_borders), pref = preferences.cropBorders)
                CheckboxItem(label = stringResource(MR.strings.pref_landscape_zoom), pref = preferences.landscapeZoom)
            }
        }
    }
}

/** The page around the image: background, what the screen shows, transitions, and the E-Ink flash. */
@Composable
internal fun ColumnScope.MangaAppearancePage(viewModel: ReaderSettingsViewModel) {
    val preferences = viewModel.preferences
    val viewer by viewModel.viewerFlow.collectAsState()

    val readerTheme by preferences.readerTheme.collectAsState()
    SettingsChipRow(MR.strings.pref_reader_theme) {
        readerThemes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { preferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
    val titleFormat by preferences.chapterTitleFormat.collectAsState()
    SettingsChipRow(MR.strings.pref_chapter_title_format) {
        ChapterTitleFormat.entries.forEach {
            FilterChip(
                selected = titleFormat == it,
                onClick = { preferences.chapterTitleFormat.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
    CheckboxItem(label = stringResource(MR.strings.pref_show_page_number), pref = preferences.showPageNumber)
    CheckboxItem(label = stringResource(MR.strings.pref_fullscreen), pref = preferences.fullscreen)
    val isFullscreen by preferences.fullscreen.collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(label = stringResource(MR.strings.pref_cutout_short), pref = preferences.drawUnderCutout)
    }
    CheckboxItem(label = stringResource(MR.strings.pref_keep_screen_on), pref = preferences.keepScreenOn)
    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = preferences.alwaysShowChapterTransition,
    )
    CheckboxItem(label = stringResource(MR.strings.pref_page_transitions), pref = preferences.pageTransitions)

    val webGpu = (viewer.running() as? RunningViewer.WebGpu)?.viewer
    if (webGpu != null && webGpu.isDualPageMode()) {
        TransitionAnimationRow(
            titleRes = MR.strings.pref_transition_animation_dual,
            pref = preferences.transitionAnimationDual,
            entries = ReaderPreferences.TransitionAnimation.entries -
                ReaderPreferences.TransitionAnimation.FLIP_LEFT -
                ReaderPreferences.TransitionAnimation.FLIP_RIGHT,
        )
        CutoutModeRow(MR.strings.pref_cutout_mode_dual, preferences.cutoutModeDual)
    } else if (webGpu != null && !webGpu.isContinuous) {
        TransitionAnimationRow(
            titleRes = MR.strings.pref_transition_animation,
            pref = preferences.transitionAnimation,
            entries = ReaderPreferences.TransitionAnimation.entries,
        )
        CutoutModeRow(MR.strings.pref_cutout_mode, preferences.cutoutMode)
    }

    val flashPageState by preferences.flashOnPageChange.collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_flash_page), pref = preferences.flashOnPageChange)
    if (flashPageState) {
        val flashMillis by preferences.flashDurationMillis.collectAsState()
        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(MR.strings.pref_flash_duration),
            valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { preferences.flashDurationMillis.set(it * ReaderPreferences.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        val flashInterval by preferences.flashPageInterval.collectAsState()
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = { preferences.flashPageInterval.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        val flashColor by preferences.flashColor.collectAsState()
        SettingsChipRow(MR.strings.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { preferences.flashColor.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

/** How a touch drives the reader: tap zones, panning and zoom gestures, the long tap, the rail. */
@Composable
internal fun ColumnScope.MangaControlsPage(viewModel: ReaderSettingsViewModel) {
    val preferences = viewModel.preferences
    val viewer by viewModel.viewerFlow.collectAsState()

    when (val running = viewer.running()) {
        RunningViewer.Pager -> {
            PagerTapZones(preferences)
            CheckboxItem(label = stringResource(MR.strings.pref_navigate_pan), pref = preferences.navigateToPan)
        }
        RunningViewer.Webtoon -> {
            val navigationModeWebtoon by preferences.navigationModeWebtoon.collectAsState()
            val webtoonNavInverted by preferences.webtoonNavInverted.collectAsState()
            TapZonesRows(
                selected = navigationModeWebtoon,
                onSelect = preferences.navigationModeWebtoon::set,
                invertMode = webtoonNavInverted,
                onSelectInvertMode = preferences.webtoonNavInverted::set,
            )
            CheckboxItem(
                label = stringResource(MR.strings.pref_double_tap_zoom),
                pref = preferences.webtoonDoubleTapZoomEnabled,
            )
            CheckboxItem(
                label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                pref = preferences.webtoonDisableZoomOut,
            )
        }
        is RunningViewer.WebGpu -> {
            PagerTapZones(preferences)
            val webGpu = running.viewer
            if (webGpu.isContinuous) {
                CheckboxItem(
                    label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                    pref = preferences.webtoonDisableZoomOut,
                )
            } else if (!webGpu.isDualPageMode()) {
                CheckboxItem(label = stringResource(MR.strings.pref_navigate_pan), pref = preferences.navigateToPan)
            }
        }
    }

    CheckboxItem(label = stringResource(MR.strings.pref_read_with_long_tap), pref = preferences.readWithLongTap)

    val showNavigator by preferences.showNavigator.collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_show_progress_navigator), pref = preferences.showNavigator)
    if (!showNavigator) return
    val verticalNavigatorModes by preferences.verticalNavigator.collectAsState()
    SettingsChipRow(MR.strings.pref_vertical_navigator) {
        ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.forEach { mode ->
            FilterChip(
                selected = verticalNavigatorModes.contains(mode),
                onClick = {
                    val modes = verticalNavigatorModes
                    preferences.verticalNavigator.set(if (mode in modes) modes - mode else modes + mode)
                },
                label = { Text(stringResource(mode.stringRes)) },
            )
        }
    }
    if (verticalNavigatorModes.isNotEmpty()) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
            pref = preferences.verticalNavigatorOnLeft,
        )
        val verticalNavigatorHeight by preferences.verticalNavigatorHeight.collectAsState()
        SliderItem(
            label = stringResource(MR.strings.pref_vertical_navigator_height),
            value = verticalNavigatorHeight,
            valueRange = 65..100,
            steps = 6,
            onChange = { preferences.verticalNavigatorHeight.set(it) },
        )
    }
}

@Composable
private fun ColumnScope.ImageScaleTypeRow(
    preferences: ReaderPreferences,
    offered: List<StringResource>,
) {
    val imageScaleType by preferences.imageScaleType.collectAsState()
    SettingsChipRow(MR.strings.pref_image_scale_type) {
        offered.forEach {
            FilterChip(
                selected = ReaderPreferences.ImageScaleType[imageScaleType - 1] == it,
                onClick = { preferences.imageScaleType.set(ReaderPreferences.ImageScaleType.indexOf(it) + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.ZoomStartRow(preferences: ReaderPreferences) {
    val zoomStart by preferences.zoomStart.collectAsState()
    SettingsChipRow(MR.strings.pref_zoom_start) {
        ReaderPreferences.ZoomStart.mapIndexed { index, it ->
            FilterChip(
                selected = zoomStart == index + 1,
                onClick = { preferences.zoomStart.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.DualPageRows(
    split: Preference<Boolean>,
    invert: Preference<Boolean>,
    rotate: Preference<Boolean>,
    rotateInvert: Preference<Boolean>,
) {
    val splitOn by split.collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_dual_page_split), pref = split)
    if (splitOn) CheckboxItem(label = stringResource(MR.strings.pref_dual_page_invert), pref = invert)
    val rotateOn by rotate.collectAsState()
    CheckboxItem(label = stringResource(MR.strings.pref_page_rotate), pref = rotate)
    if (rotateOn) CheckboxItem(label = stringResource(MR.strings.pref_page_rotate_invert), pref = rotateInvert)
}

@Composable
private fun ColumnScope.TransitionAnimationRow(
    titleRes: StringResource,
    pref: Preference<ReaderPreferences.TransitionAnimation>,
    entries: List<ReaderPreferences.TransitionAnimation>,
) {
    val selected by pref.collectAsState()
    SettingsChipRow(titleRes) {
        entries.forEach {
            FilterChip(
                selected = it == selected,
                onClick = { pref.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.CutoutModeRow(
    titleRes: StringResource,
    pref: Preference<ReaderPreferences.CutoutMode>,
) {
    val selected by pref.collectAsState()
    SettingsChipRow(titleRes) {
        ReaderPreferences.CutoutMode.entries.forEach {
            FilterChip(
                selected = it == selected,
                onClick = { pref.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.PagerTapZones(preferences: ReaderPreferences) {
    val navigationModePager by preferences.navigationModePager.collectAsState()
    val pagerNavInverted by preferences.pagerNavInverted.collectAsState()
    TapZonesRows(
        selected = navigationModePager,
        onSelect = preferences.navigationModePager::set,
        invertMode = pagerNavInverted,
        onSelectInvertMode = preferences.pagerNavInverted::set,
    )
}

@Composable
private fun ColumnScope.TapZonesRows(
    selected: Int,
    onSelect: (Int) -> Unit,
    invertMode: ReaderPreferences.TappingInvertMode,
    onSelectInvertMode: (ReaderPreferences.TappingInvertMode) -> Unit,
) {
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        ReaderPreferences.TapZones.mapIndexed { index, it ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }
    // 5 is Disabled, which has no zones to invert.
    if (selected != 5) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            ReaderPreferences.TappingInvertMode.entries.map {
                FilterChip(
                    selected = it == invertMode,
                    onClick = { onSelectInvertMode(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
}
