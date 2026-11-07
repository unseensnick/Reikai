package reikai.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.flow.StateFlow
import reikai.presentation.reader.ReaderFlash
import reikai.presentation.reader.navigation.MangaTapLayouts
import reikai.presentation.reader.navigation.setTapLayout
import reikai.presentation.reader.showsTwoPages
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

/**
 * Upstream's sheet rule for dual pages (WebGpuViewerSettings), read from observed state so the rows
 * follow the Dual page view chip and a rotation. The viewer's own isDualPageMode reads neither.
 */
@Composable
private fun isWebGpuDualPage(webGpu: WebGpuViewer, preferences: ReaderPreferences): Boolean {
    val dualPageView by preferences.dualPageView.collectAsState()
    val configuration = LocalConfiguration.current
    return !webGpu.isContinuous && dualPageView.showsTwoPages(configuration.screenWidthDp, configuration.screenHeightDp)
}

private val readerThemes = listOf(
    MR.strings.black_background to ReaderPreferences.ReaderTheme.BLACK.value,
    MR.strings.gray_background to ReaderPreferences.ReaderTheme.GRAY.value,
    MR.strings.white_background to ReaderPreferences.ReaderTheme.WHITE.value,
    MR.strings.automatic_background to ReaderPreferences.ReaderTheme.AUTOMATIC.value,
)

/** This series' reading mode and rotation, then how the running viewer lays a page out. */
@Composable
internal fun ColumnScope.MangaReadingPage(viewModel: ReaderSettingsViewModel, viewerFlow: StateFlow<Viewer?>) {
    val preferences = viewModel.preferences
    val manga by viewModel.mangaFlow.collectAsState()
    val viewer by viewerFlow.collectAsState()

    HeadingItem(MR.strings.pref_category_for_this_series)
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    SettingsChipRow(MR.strings.pref_category_reading_mode) {
        ReadingMode.entries.map {
            FilterChip(
                selected = it == readingMode,
                onClick = { viewModel.setReadingMode(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }
    EntryRotationRow(manga?.readerOrientation?.toInt(), viewModel::setOrientation)

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
            if (!webGpu.isContinuous) {
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
                // The long strip crops by cropBordersWebtoon; upstream's row binds cropBorders, which it ignores.
                CheckboxItem(
                    label = stringResource(MR.strings.pref_crop_borders),
                    pref = preferences.cropBordersWebtoon,
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
            } else if (!isWebGpuDualPage(webGpu, preferences)) {
                ImageScaleTypeRow(preferences, ReaderPreferences.ImageScaleTypeWebGpuViewer)
                ZoomStartRow(preferences)
                CheckboxItem(label = stringResource(MR.strings.pref_crop_borders), pref = preferences.cropBorders)
                val cropBorders by preferences.cropBorders.collectAsState()
                if (cropBorders) {
                    CheckboxItem(
                        label = stringResource(MR.strings.pref_crop_instead_of_zoom),
                        pref = preferences.cropInsteadOfZoom,
                    )
                }
                CheckboxItem(label = stringResource(MR.strings.pref_landscape_zoom), pref = preferences.landscapeZoom)
            }
        }
    }
}

/** The page around the image: background, what the screen shows, transitions, and the E-Ink flash. */
@Composable
internal fun ColumnScope.MangaAppearancePage(viewModel: ReaderSettingsViewModel, viewerFlow: StateFlow<Viewer?>) {
    val preferences = viewModel.preferences
    val viewer by viewerFlow.collectAsState()

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
    ReaderPageRows(
        titleFormat = preferences.chapterTitleFormat,
        progressLabel = MR.strings.pref_show_page_number,
        showProgress = preferences.showPageNumber,
        showTimeLeft = preferences.showTimeLeft,
        progressSize = preferences.progressSize,
        progressBacked = preferences.progressBacked,
        fullscreen = preferences.fullscreen,
        drawUnderCutout = preferences.drawUnderCutout,
        keepScreenOn = preferences.keepScreenOn,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = preferences.alwaysShowChapterTransition,
    )
    CheckboxItem(label = stringResource(MR.strings.pref_page_transitions), pref = preferences.pageTransitions)

    val webGpu = (viewer.running() as? RunningViewer.WebGpu)?.viewer
    if (webGpu != null && isWebGpuDualPage(webGpu, preferences)) {
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

    ReaderFlashRows(ReaderFlash.manga(preferences))
}

/** How a touch drives the reader: tap zones, panning and zoom gestures, the long tap, the rail. */
@Composable
internal fun ColumnScope.MangaControlsPage(viewModel: ReaderSettingsViewModel, viewerFlow: StateFlow<Viewer?>) {
    val preferences = viewModel.preferences
    val viewer by viewerFlow.collectAsState()

    MangaTapZones(preferences, pager = !viewModel.usesWebtoonZones)
    when (val running = viewer.running()) {
        RunningViewer.Pager -> {
            CheckboxItem(label = stringResource(MR.strings.pref_navigate_pan), pref = preferences.navigateToPan)
        }
        RunningViewer.Webtoon -> {
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
            val webGpu = running.viewer
            if (webGpu.isContinuous) {
                CheckboxItem(
                    label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                    pref = preferences.webtoonDisableZoomOut,
                )
            } else if (!isWebGpuDualPage(webGpu, preferences)) {
                CheckboxItem(label = stringResource(MR.strings.pref_navigate_pan), pref = preferences.navigateToPan)
            }
            CheckboxItem(
                label = stringResource(MR.strings.pref_disable_double_tap_gestures),
                pref = preferences.disableDoubleTapGestures,
            )
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
        ReaderRailRows(onLeft = preferences.verticalNavigatorOnLeft, height = preferences.verticalNavigatorHeight)
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
                selected = imageScaleType == ReaderPreferences.ImageScaleType.indexOf(it) + 1,
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

/** The pager's tap zones, which WebGPU reads too, or the long strip's. */
@Composable
private fun ColumnScope.MangaTapZones(preferences: ReaderPreferences, pager: Boolean) {
    val layoutPref = if (pager) preferences.navigationModePager else preferences.navigationModeWebtoon
    val invertPref = if (pager) preferences.pagerNavInverted else preferences.webtoonNavInverted
    val selected by layoutPref.collectAsState()
    val invert by invertPref.collectAsState()
    TapZonesRows(
        names = MangaTapLayouts.names,
        selected = selected,
        onSelect = { preferences.setTapLayout(pager, it) },
        layout = MangaTapLayouts.layout(selected),
        offersInvert = MangaTapLayouts.offersInvert(selected),
        invertModes = MangaTapLayouts.invertModes(selected),
        invert = invert,
        onInvert = invertPref::set,
        bottomZoneHeight = preferences.tapBottomZoneHeight,
    )
}
