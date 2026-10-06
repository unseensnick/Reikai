package reikai.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import kotlinx.coroutines.flow.Flow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Palette
import reikai.domain.novel.NovelPreferences
import reikai.domain.reader.ChapterTitleFormat
import reikai.novel.font.NovelFont
import reikai.presentation.icons.Contrast
import reikai.presentation.icons.RecordVoiceOver
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.icons.TouchApp
import reikai.presentation.reader.ReaderDisplayFilters
import reikai.presentation.reader.ReaderRanges
import reikai.presentation.reader.ReaderTextSettings
import reikai.presentation.reader.ViewportAutoScroll
import reikai.presentation.reader.autoScrollSpeedLabel
import reikai.util.scaled
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * What the in-reader settings sheet edits, answered per content type so the compiler makes both
 * readers fill every tab. The filters are the session's own set, shared by both page sets.
 */
sealed interface ReaderSettingsPages {
    val filters: ReaderDisplayFilters

    data class Manga(
        val viewModel: ReaderSettingsViewModel,
        override val filters: ReaderDisplayFilters,
    ) : ReaderSettingsPages

    data class Novel(
        val preferences: NovelPreferences,
        val textSettings: ReaderTextSettings,
        override val filters: ReaderDisplayFilters,
        /** The novel's own rotation flag and its setter, as the session answers them. */
        val orientation: Flow<Int>,
        val onChangeOrientation: (Int) -> Unit,
        /** The fonts the user added, listed off the main thread when the font picker opens. */
        val installedFonts: suspend () -> List<NovelFont>,
    ) : ReaderSettingsPages
}

/**
 * The tabs in their order. Both readers share all of them but Read aloud, which only a novel has, so the
 * sheet reads the same whichever is open.
 */
private enum class ReaderSettingsTab(val titleRes: StringResource, val icon: ImageVector) {
    Reading(MR.strings.pref_category_reading, MaterialSymbols.Rounded.LocalLibrary),
    Appearance(MR.strings.pref_category_appearance, MaterialSymbols.Rounded.Palette),
    Controls(MR.strings.reader_settings_controls, ReikaiIcons.TouchApp),
    ReadAloud(MR.strings.pref_category_read_aloud, ReikaiIcons.RecordVoiceOver),
    Filters(MR.strings.reader_settings_filters, ReikaiIcons.Contrast),
}

/**
 * The reader's settings sheet, opened from the bar's gear. The Filters tab drops the dim and the menus
 * so the page it tints stays in view.
 */
@Composable
fun ReaderSettingsSheet(
    pages: ReaderSettingsPages,
    autoScrollRunning: Boolean,
    onToggleAutoScroll: () -> Unit,
    /** How the viewport showing now auto-scrolls, which decides whether the rate is an interval or a speed. */
    autoScroll: ViewportAutoScroll?,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
) {
    val tabs = ReaderSettingsTab.entries.filter {
        it != ReaderSettingsTab.ReadAloud ||
            pages is ReaderSettingsPages.Novel
    }
    val tabTitles = tabs.map { stringResource(it.titleRes) }
    val pagerState = rememberPagerState { tabs.size }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = {
                onDismissRequest()
                onShowMenus()
            },
            tabTitles = tabTitles,
            pagerState = pagerState,
            tabIcons = tabs.map { it.icon },
        ) { page ->
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window

            LaunchedEffect(pagerState.currentPage) {
                if (tabs[pagerState.currentPage] == ReaderSettingsTab.Filters) {
                    window?.setDimAmount(0f)
                    onHideMenus()
                } else {
                    window?.setDimAmount(0.5f)
                    onShowMenus()
                }
            }

            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tabs[page]) {
                    ReaderSettingsTab.Reading -> when (pages) {
                        is ReaderSettingsPages.Manga -> MangaReadingPage(pages.viewModel)
                        is ReaderSettingsPages.Novel -> NovelReadingPage(pages)
                    }
                    ReaderSettingsTab.Appearance -> when (pages) {
                        is ReaderSettingsPages.Manga -> MangaAppearancePage(pages.viewModel)
                        is ReaderSettingsPages.Novel -> NovelAppearancePage(pages)
                    }
                    ReaderSettingsTab.Controls -> {
                        AutoScrollRows(autoScrollRunning, onToggleAutoScroll, autoScroll)
                        when (pages) {
                            is ReaderSettingsPages.Manga -> MangaControlsPage(pages.viewModel)
                            is ReaderSettingsPages.Novel -> NovelControlsPage(pages.preferences)
                        }
                    }
                    ReaderSettingsTab.ReadAloud -> if (pages is ReaderSettingsPages.Novel) {
                        NovelReadAloudPage(pages.preferences)
                    }
                    ReaderSettingsTab.Filters -> ReaderFiltersPage(pages.filters)
                }
            }
        }
    }
}

/** Whether auto-scroll runs now, and its rate, the same rows for either reader. */
@Composable
private fun ColumnScope.AutoScrollRows(running: Boolean, onToggle: () -> Unit, shape: ViewportAutoScroll?) {
    CheckboxItem(label = stringResource(MR.strings.pref_auto_scroll), checked = running, onClick = onToggle)
    when (shape) {
        is ViewportAutoScroll.Stepped -> {
            val seconds by shape.intervalSeconds.collectAsState()
            SliderItem(
                value = seconds,
                valueRange = ReaderRanges.autoScrollIntervalSeconds,
                label = stringResource(MR.strings.pref_auto_scroll_interval),
                valueString = stringResource(MR.strings.seconds_short, seconds),
                onChange = shape.intervalSeconds::set,
                pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        is ViewportAutoScroll.Continuous -> {
            val speedPref = shape.speed.scaled(ReaderRanges.TENTHS)
            val speed by speedPref.collectAsState()
            SliderItem(
                value = speed,
                valueRange = ReaderRanges.autoScrollSpeedTenths,
                label = stringResource(MR.strings.pref_auto_scroll_speed),
                valueString = autoScrollSpeedLabel(speed),
                onChange = speedPref::set,
                pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        null -> Unit
    }
}

/** The entry's own rotation, which both readers store per entry and offer first on the Reading tab. */
@Composable
internal fun ColumnScope.EntryRotationRow(flagValue: Int?, onChange: (ReaderOrientation) -> Unit) {
    val selected = ReaderOrientation.fromPreference(flagValue)
    SettingsChipRow(MR.strings.rotation_type) {
        ReaderOrientation.entries.map {
            FilterChip(
                selected = it == selected,
                onClick = { onChange(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }
}

/**
 * What the screen around the page shows, the same rows in the same order for either reader. Only the
 * progress label differs: manga counts pages, a novel shows a percentage.
 */
@Composable
internal fun ColumnScope.ReaderPageRows(
    titleFormat: Preference<ChapterTitleFormat>,
    progressLabel: StringResource,
    showProgress: Preference<Boolean>,
    fullscreen: Preference<Boolean>,
    drawUnderCutout: Preference<Boolean>,
    keepScreenOn: Preference<Boolean>,
) {
    val selectedFormat by titleFormat.collectAsState()
    SettingsChipRow(MR.strings.pref_chapter_title_format) {
        ChapterTitleFormat.entries.forEach {
            FilterChip(
                selected = selectedFormat == it,
                onClick = { titleFormat.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }
    CheckboxItem(label = stringResource(progressLabel), pref = showProgress)
    CheckboxItem(label = stringResource(MR.strings.pref_fullscreen), pref = fullscreen)
    val isFullscreen by fullscreen.collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(label = stringResource(MR.strings.pref_cutout_short), pref = drawUnderCutout)
    }
    CheckboxItem(label = stringResource(MR.strings.pref_keep_screen_on), pref = keepScreenOn)
}

/** Which side the rail sits on and how tall it is, once the reader's own gate has shown the rail. */
@Composable
internal fun ColumnScope.ReaderRailRows(onLeft: Preference<Boolean>, height: Preference<Int>) {
    CheckboxItem(label = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left), pref = onLeft)
    val railHeight by height.collectAsState()
    SliderItem(
        value = railHeight,
        valueRange = ReaderRanges.railHeightPercent,
        steps = ReaderRanges.railHeightSteps,
        label = stringResource(MR.strings.pref_vertical_navigator_height),
        onChange = height::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}
