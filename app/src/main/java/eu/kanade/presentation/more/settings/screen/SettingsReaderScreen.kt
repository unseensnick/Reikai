package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import reikai.domain.novel.NovelPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.NumberFormat
import kotlin.math.roundToInt

object SettingsReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPref = remember { Injekt.get<ReaderPreferences>() }
        // RK: novel reader prefs, surfaced as content-type sub-groups ("Reading / Accessibility · Novels").
        val novelPref = remember { Injekt.get<NovelPreferences>() }

        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.defaultReadingMode,
                entries = ReadingMode.entries.drop(1)
                    .associate { it.flagValue to stringResource(it.stringRes) },
                title = stringResource(MR.strings.pref_viewer_type),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.doubleTapAnimSpeed,
                entries = mapOf(
                    1 to stringResource(MR.strings.double_tap_anim_speed_0),
                    500 to stringResource(MR.strings.double_tap_anim_speed_normal),
                    250 to stringResource(MR.strings.double_tap_anim_speed_fast),
                ),
                title = stringResource(MR.strings.pref_double_tap_anim_speed),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.showReadingMode,
                title = stringResource(MR.strings.pref_show_reading_mode),
                subtitle = stringResource(MR.strings.pref_show_reading_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.showNavigationOverlayOnStart,
                title = stringResource(MR.strings.pref_show_navigation_mode),
                subtitle = stringResource(MR.strings.pref_show_navigation_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.pageTransitions,
                title = stringResource(MR.strings.pref_page_transitions),
            ),
            getDisplayGroup(readerPreferences = readerPref),
            getEInkGroup(readerPreferences = readerPref),
            getReadingGroup(readerPreferences = readerPref),
            // RK --> light-novel reader settings as content-type sub-groups on the shared Reader screen.
            getNovelReadingGroup(novelPreferences = novelPref),
            getNovelAccessibilityGroup(novelPreferences = novelPref),
            // RK <--
            getPagedGroup(readerPreferences = readerPref),
            getWebtoonGroup(readerPreferences = readerPref),
            getNavigationGroup(readerPreferences = readerPref, novelPreferences = novelPref),
            getActionsGroup(readerPreferences = readerPref),
        )
    }

    @Composable
    private fun getDisplayGroup(
        readerPreferences: ReaderPreferences,
    ): Preference.PreferenceGroup {
        val fullscreen by readerPreferences.fullscreen.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.defaultOrientationType,
                    entries = ReaderOrientation.entries.drop(1)
                        .associate { it.flagValue to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_rotation_type),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readerTheme,
                    entries = mapOf(
                        1 to stringResource(MR.strings.black_background),
                        2 to stringResource(MR.strings.gray_background),
                        0 to stringResource(MR.strings.white_background),
                        3 to stringResource(MR.strings.automatic_background),
                    ),
                    title = stringResource(MR.strings.pref_reader_theme),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.fullscreen,
                    title = stringResource(MR.strings.pref_fullscreen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.drawUnderCutout,
                    title = stringResource(MR.strings.pref_cutout_short),
                    enabled = LocalView.current.hasDisplayCutout() && fullscreen,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.keepScreenOn,
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showPageNumber,
                    title = stringResource(MR.strings.pref_show_page_number),
                ),
            ),
        )
    }

    @Composable
    private fun getEInkGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val flashPageState by readerPreferences.flashOnPageChange.collectAsState()

        val flashMillisPref = readerPreferences.flashDurationMillis
        val flashMillis by flashMillisPref.collectAsState()

        val flashIntervalPref = readerPreferences.flashPageInterval
        val flashInterval by flashIntervalPref.collectAsState()

        val flashColorPref = readerPreferences.flashColor

        return Preference.PreferenceGroup(
            title = "E-Ink",
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.flashOnPageChange,
                    title = stringResource(MR.strings.pref_flash_page),
                    subtitle = stringResource(MR.strings.pref_flash_page_summ),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
                    valueRange = 1..15,
                    title = stringResource(MR.strings.pref_flash_duration),
                    valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
                    enabled = flashPageState,
                    onValueChanged = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashInterval,
                    valueRange = 1..10,
                    title = stringResource(MR.strings.pref_flash_page_interval),
                    valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
                    enabled = flashPageState,
                    onValueChanged = { flashIntervalPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = flashColorPref,
                    entries = mapOf(
                        ReaderPreferences.FlashColor.BLACK to stringResource(MR.strings.pref_flash_style_black),
                        ReaderPreferences.FlashColor.WHITE to stringResource(MR.strings.pref_flash_style_white),
                        ReaderPreferences.FlashColor.WHITE_BLACK
                            to stringResource(MR.strings.pref_flash_style_white_black),
                    ),
                    title = stringResource(MR.strings.pref_flash_with),
                    enabled = flashPageState,
                ),
            ),
        )
    }

    @Composable
    private fun getReadingGroup(
        readerPreferences: ReaderPreferences,
    ): Preference.PreferenceGroup {
        // RK: collected for the preload slider (Y-feature)
        val preloadSizePref = readerPreferences.preloadSize
        val preloadSize by preloadSizePref.collectAsState()
        return Preference.PreferenceGroup(
            // RK: content-type suffix so it pairs with the "Reading · Novels" group below.
            title = contentTypedCategory(MR.strings.pref_category_reading, MR.strings.content_type_manga),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipRead,
                    title = stringResource(MR.strings.pref_skip_read_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipFiltered,
                    title = stringResource(MR.strings.pref_skip_filtered_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipDupe,
                    title = stringResource(MR.strings.pref_skip_dupe_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.markReadOnSkip,
                    title = stringResource(MR.strings.pref_mark_read_on_skip),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = readerPreferences.readerBottomButtons,
                    entries = ReaderBottomButton.offeredIn(ReaderBottomButton.Scope.Manga)
                        .associate { it.value to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_reader_bottom_buttons),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.preserveReadingPosition,
                    title = stringResource(MR.strings.pref_preserve_reading_position),
                    subtitle = stringResource(MR.strings.pref_preserve_reading_position_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = preloadSize,
                    valueRange = 1..20,
                    title = stringResource(MR.strings.pref_reader_preload_size),
                    valueString = pluralStringResource(MR.plurals.pref_pages, preloadSize, preloadSize),
                    onValueChanged = { preloadSizePref.set(it) },
                ),
                // RK <--
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.alwaysShowChapterTransition,
                    title = stringResource(MR.strings.pref_always_show_chapter_transition),
                ),
            ),
        )
    }

    // RK --> light-novel reader settings, the novel twins of the Reading group above. The live-tuning
    // display controls (font, size, theme) stay in the in-reader gear sheet, which previews them.
    @Composable
    private fun getNovelReadingGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = contentTypedCategory(MR.strings.pref_category_reading, MR.strings.content_type_novels),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.readerDefaultOrientation(),
                    entries = ReaderOrientation.entries
                        .filter { it != ReaderOrientation.DEFAULT && it != ReaderOrientation.REVERSE_PORTRAIT }
                        .associate { it.flagValue to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_rotation_type),
                    subtitle = "%s",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerTapToScroll(),
                    title = stringResource(MR.strings.pref_tap_to_scroll),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSwipeGestures(),
                    title = stringResource(MR.strings.pref_swipe_between_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerSkipDuplicateChapters(),
                    title = stringResource(MR.strings.pref_skip_dupe_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerMarkReadOnSkip(),
                    title = stringResource(MR.strings.pref_mark_read_on_skip),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerAutoScroll(),
                    title = stringResource(MR.strings.pref_auto_scroll),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.readerBottomButtons(),
                    entries = ReaderBottomButton.offeredIn(ReaderBottomButton.Scope.Novel)
                        .associate { it.value to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_reader_bottom_buttons),
                ),
            ),
        )

    @Composable
    private fun getNovelAccessibilityGroup(novelPreferences: NovelPreferences): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = contentTypedCategory(MR.strings.pref_category_accessibility, MR.strings.content_type_novels),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerKeepScreenOn(),
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerShowProgressPercentage(),
                    title = stringResource(MR.strings.pref_show_reading_progress),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerBionicReading(),
                    title = stringResource(MR.strings.pref_bionic_reading),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerRemoveExtraSpacing(),
                    title = stringResource(MR.strings.pref_remove_extra_spacing),
                ),
            ),
        )
    // RK <--

    @Composable
    private fun getPagedGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val navModePref = readerPreferences.navigationModePager
        val imageScaleTypePref = readerPreferences.imageScaleType
        val dualPageSplitPref = readerPreferences.dualPageSplitPaged
        val rotateToFitPref = readerPreferences.dualPageRotateToFit

        val navMode by navModePref.collectAsState()
        val imageScaleType by imageScaleTypePref.collectAsState()
        val dualPageSplit by dualPageSplitPref.collectAsState()
        val rotateToFit by rotateToFitPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pager_viewer),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = navModePref,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, it -> index to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.pagerNavInverted,
                    entries = listOf(
                        ReaderPreferences.TappingInvertMode.NONE,
                        ReaderPreferences.TappingInvertMode.HORIZONTAL,
                        ReaderPreferences.TappingInvertMode.VERTICAL,
                        ReaderPreferences.TappingInvertMode.BOTH,
                    )
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = imageScaleTypePref,
                    entries = ReaderPreferences.ImageScaleType
                        .mapIndexed { index, it -> index + 1 to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_image_scale_type),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.zoomStart,
                    entries = ReaderPreferences.ZoomStart
                        .mapIndexed { index, it -> index + 1 to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_zoom_start),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.cropBorders,
                    title = stringResource(MR.strings.pref_crop_borders),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.landscapeZoom,
                    title = stringResource(MR.strings.pref_landscape_zoom),
                    enabled = imageScaleType == 1,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.navigateToPan,
                    title = stringResource(MR.strings.pref_navigate_pan),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = dualPageSplitPref,
                    title = stringResource(MR.strings.pref_dual_page_split),
                    onValueChanged = {
                        rotateToFitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageInvertPaged,
                    title = stringResource(MR.strings.pref_dual_page_invert),
                    subtitle = stringResource(MR.strings.pref_dual_page_invert_summary),
                    enabled = dualPageSplit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rotateToFitPref,
                    title = stringResource(MR.strings.pref_page_rotate),
                    onValueChanged = {
                        dualPageSplitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageRotateToFitInvert,
                    title = stringResource(MR.strings.pref_page_rotate_invert),
                    enabled = rotateToFit,
                ),
            ),
        )
    }

    @Composable
    private fun getWebtoonGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val numberFormat = remember { NumberFormat.getPercentInstance() }

        val navModePref = readerPreferences.navigationModeWebtoon
        val dualPageSplitPref = readerPreferences.dualPageSplitWebtoon
        val rotateToFitPref = readerPreferences.dualPageRotateToFitWebtoon
        val webtoonSidePaddingPref = readerPreferences.webtoonSidePadding

        val navMode by navModePref.collectAsState()
        val dualPageSplit by dualPageSplitPref.collectAsState()
        val rotateToFit by rotateToFitPref.collectAsState()
        val webtoonSidePadding by webtoonSidePaddingPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.webtoon_viewer),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = navModePref,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, it -> index to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.webtoonNavInverted,
                    entries = listOf(
                        ReaderPreferences.TappingInvertMode.NONE,
                        ReaderPreferences.TappingInvertMode.HORIZONTAL,
                        ReaderPreferences.TappingInvertMode.VERTICAL,
                        ReaderPreferences.TappingInvertMode.BOTH,
                    )
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = webtoonSidePadding,
                    valueRange = ReaderPreferences.let {
                        it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX
                    },
                    title = stringResource(MR.strings.pref_webtoon_side_padding),
                    valueString = numberFormat.format(webtoonSidePadding / 100f),
                    onValueChanged = { webtoonSidePaddingPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readerHideThreshold,
                    entries = mapOf(
                        ReaderPreferences.ReaderHideThreshold.HIGHEST to stringResource(MR.strings.pref_highest),
                        ReaderPreferences.ReaderHideThreshold.HIGH to stringResource(MR.strings.pref_high),
                        ReaderPreferences.ReaderHideThreshold.LOW to stringResource(MR.strings.pref_low),
                        ReaderPreferences.ReaderHideThreshold.LOWEST to stringResource(MR.strings.pref_lowest),
                    ),
                    title = stringResource(MR.strings.pref_hide_threshold),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.cropBordersWebtoon,
                    title = stringResource(MR.strings.pref_crop_borders),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = dualPageSplitPref,
                    title = stringResource(MR.strings.pref_dual_page_split),
                    onValueChanged = {
                        rotateToFitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageInvertWebtoon,
                    title = stringResource(MR.strings.pref_dual_page_invert),
                    subtitle = stringResource(MR.strings.pref_dual_page_invert_summary),
                    enabled = dualPageSplit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rotateToFitPref,
                    title = stringResource(MR.strings.pref_page_rotate),
                    onValueChanged = {
                        dualPageSplitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageRotateToFitInvertWebtoon,
                    title = stringResource(MR.strings.pref_page_rotate_invert),
                    enabled = rotateToFit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDoubleTapZoomEnabled,
                    title = stringResource(MR.strings.pref_double_tap_zoom),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDisableZoomOut,
                    title = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                ),
            ),
        )
    }

    @Composable
    private fun getNavigationGroup(
        readerPreferences: ReaderPreferences,
        novelPreferences: NovelPreferences,
    ): Preference.PreferenceGroup {
        val readWithVolumeKeysPref = readerPreferences.readWithVolumeKeys
        val readWithVolumeKeys by readWithVolumeKeysPref.collectAsState()
        // RK: volume-key scroll amount (long-strip viewers), novel-reader parity
        val volumeScrollAmountPref = readerPreferences.readWithVolumeKeysScrollAmount
        val volumeScrollAmount by volumeScrollAmountPref.collectAsState()
        val volumeScrollPercent = (volumeScrollAmount * 100).roundToInt()

        // RK: novel volume-key twins, co-located with the manga rows below (content-type labels)
        val useVolumeButtonsPref = novelPreferences.readerUseVolumeButtons()
        val useVolumeButtons by useVolumeButtonsPref.collectAsState()
        val volumeButtonsFractionPref = novelPreferences.readerVolumeButtonsFraction()
        val volumeButtonsFraction by volumeButtonsFractionPref.collectAsState()
        val volumeButtonsPercent = (volumeButtonsFraction * 100).roundToInt()

        val verticalNavigator by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigatorHeightPref = readerPreferences.verticalNavigatorHeight
        val verticalNavigatorHeight by verticalNavigatorHeightPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = listOf(
                // RK --> volume-key rows for both content types, labeled so the twins read distinctly
                Preference.PreferenceItem.SwitchPreference(
                    preference = readWithVolumeKeysPref,
                    title = contentTypedCategory(MR.strings.pref_read_with_volume_keys, MR.strings.content_type_manga),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeysInverted,
                    title = contentTypedCategory(
                        MR.strings.pref_read_with_volume_keys_inverted,
                        MR.strings.content_type_manga,
                    ),
                    enabled = readWithVolumeKeys,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = volumeScrollPercent,
                    valueRange = 25..100,
                    title = contentTypedCategory(
                        MR.strings.pref_volume_keys_scroll_amount,
                        MR.strings.content_type_manga,
                    ),
                    subtitle = stringResource(MR.strings.pref_volume_keys_scroll_amount_long_strip),
                    valueString = "$volumeScrollPercent%",
                    enabled = readWithVolumeKeys,
                    onValueChanged = { volumeScrollAmountPref.set(it / 100f) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = useVolumeButtonsPref,
                    title = contentTypedCategory(MR.strings.pref_read_with_volume_keys, MR.strings.content_type_novels),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.readerVolumeButtonsInverted(),
                    title = contentTypedCategory(
                        MR.strings.pref_read_with_volume_keys_inverted,
                        MR.strings.content_type_novels,
                    ),
                    enabled = useVolumeButtons,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = volumeButtonsPercent,
                    valueRange = 25..100,
                    title = contentTypedCategory(
                        MR.strings.pref_volume_keys_scroll_amount,
                        MR.strings.content_type_novels,
                    ),
                    valueString = "$volumeButtonsPercent%",
                    enabled = useVolumeButtons,
                    onValueChanged = { volumeButtonsFractionPref.set(it / 100f) },
                ),
                // RK <--
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = readerPreferences.verticalNavigator,
                    entries = ReadingMode.entries.filter { it != ReadingMode.DEFAULT }
                        .associate { it to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_vertical_navigator),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.verticalNavigatorOnLeft,
                    title = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
                    enabled = verticalNavigator.isNotEmpty(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = verticalNavigatorHeight,
                    valueRange = 65..100,
                    steps = 6,
                    title = stringResource(MR.strings.pref_vertical_navigator_height),
                    onValueChanged = { verticalNavigatorHeightPref.set(it) },
                    enabled = verticalNavigator.isNotEmpty(),
                ),
            ),
        )
    }

    @Composable
    private fun getActionsGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_actions),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithLongTap,
                    title = stringResource(MR.strings.pref_read_with_long_tap),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.folderPerManga,
                    title = stringResource(MR.strings.pref_create_folder_per_manga),
                    subtitle = stringResource(MR.strings.pref_create_folder_per_manga_summary),
                ),
            ),
        )
    }
}
