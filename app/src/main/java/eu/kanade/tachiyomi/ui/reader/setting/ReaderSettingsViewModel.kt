package eu.kanade.tachiyomi.ui.reader.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.LongStripSettings
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationSettings
import eu.kanade.tachiyomi.ui.reader.viewer.PagedSettings
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.pager.SimplePagedSettings
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuLongStripSettings
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuPagedSettings
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuSettings
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.SimpleLongStripSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

/**
 * The reader's settings, each as one value re-read whenever a preference it's made of changes, so the activity and
 * viewers apply a change in one place instead of each preference calling back.
 */
class ReaderSettingsViewModel(
    readerState: StateFlow<ReaderViewModel.State>,
    // RK --> set by the host, which builds this only for a manga session
    private val onChangeReadingMode: (ReadingMode) -> Unit,
    private val onChangeOrientation: (ReaderOrientation) -> Unit,
    // RK <--
    val preferences: ReaderPreferences,
    // RK: the mode the reader actually resolved, so the quick menu can show what is on screen
    // rather than nothing. Supplied as a lambda over ReaderViewModel's own predicate, which already
    // folds in the global default and auto webtoon; deriving it from the viewer would not work,
    // since one viewer class serves both Webtoon and Continuous vertical.
    val resolvedReadingMode: () -> Int = { ReadingMode.DEFAULT.flagValue },
) : ViewModel() {

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // RK: the host keeps keep screen on, grayscale, invert, fullscreen and cutout, read per session (GeneralSettings.kt)
    val general = settings(
        preferences.readerTheme,
        preferences.readWithLongTap,
        preferences.pageTransitions,
        preferences.doubleTapAnimSpeed,
        preferences.readWithVolumeKeys,
        preferences.readWithVolumeKeysInverted,
        preferences.alwaysShowChapterTransition,
        preferences.showNavigationOverlayOnStart,
    ) {
        GeneralSettings(
            theme = ReaderPreferences.ReaderTheme.fromValue(readerTheme.get()),
            longTapEnabled = readWithLongTap.get(),
            usePageTransitions = pageTransitions.get(),
            doubleTapAnimDuration = doubleTapAnimSpeed.get(),
            volumeKeysEnabled = readWithVolumeKeys.get(),
            volumeKeysInverted = readWithVolumeKeysInverted.get(),
            alwaysShowChapterTransition = alwaysShowChapterTransition.get(),
            navigationOverlayOnStart = showNavigationOverlayOnStart.get(),
        )
    }

    /**
     * Settings every paged viewer uses.
     */
    val paged = settings(
        preferences.imageScaleType,
        preferences.zoomStart,
        preferences.cropBorders,
        preferences.landscapeZoom,
        preferences.navigateToPan,
        preferences.navigationModePager,
        preferences.pagerNavInverted,
        preferences.tapBottomZoneHeight, // RK
    ) {
        PagedSettings(
            page = PagedSettings.Page(
                imageScaleType = ReaderPreferences.ScaleType.fromValue(imageScaleType.get()),
                zoomStart = ReaderPreferences.ZoomStartType.fromValue(zoomStart.get()),
                cropBorders = cropBorders.get(),
                landscapeZoom = landscapeZoom.get(),
            ),
            navigateToPan = navigateToPan.get(),
            navigation = NavigationSettings(
                navigationModePager.get(), // RK
                pagerNavInverted.get(),
                tapBottomZoneHeight.get(), // RK
            ),
        )
    }

    /**
     * Settings every long strip viewer uses.
     */
    val longStrip = settings(
        preferences.cropBordersWebtoon,
        preferences.webtoonDisableZoomOut,
    ) {
        LongStripSettings(
            cropBorders = cropBordersWebtoon.get(),
            zoomOutDisabled = webtoonDisableZoomOut.get(),
        )
    }

    /**
     * Settings only the pager viewers use.
     */
    val simplePaged = settings(
        preferences.dualPageSplitPaged,
        preferences.dualPageInvertPaged,
        preferences.dualPageRotateToFit,
        preferences.dualPageRotateToFitInvert,
    ) {
        SimplePagedSettings(
            dualPageSplit = dualPageSplitPaged.get(),
            dualPageInvert = dualPageInvertPaged.get(),
            dualPageRotateToFit = dualPageRotateToFit.get(),
            dualPageRotateToFitInvert = dualPageRotateToFitInvert.get(),
        )
    }

    /**
     * Settings only the webtoon viewers use.
     */
    val simpleLongStrip = settings(
        preferences.webtoonSidePadding,
        preferences.dualPageSplitWebtoon,
        preferences.dualPageInvertWebtoon,
        preferences.dualPageRotateToFitWebtoon,
        preferences.dualPageRotateToFitInvertWebtoon,
        preferences.webtoonDoubleTapZoomEnabled,
        preferences.navigationModeWebtoon,
        preferences.webtoonNavInverted,
        // RK -->
        preferences.tapBottomZoneHeight,
        preferences.readWithVolumeKeysScrollAmount,
        // RK <--
    ) {
        SimpleLongStripSettings(
            page = SimpleLongStripSettings.Page(
                sidePadding = webtoonSidePadding.get(),
                dualPageSplit = dualPageSplitWebtoon.get(),
                dualPageInvert = dualPageInvertWebtoon.get(),
                dualPageRotateToFit = dualPageRotateToFitWebtoon.get(),
                dualPageRotateToFitInvert = dualPageRotateToFitInvertWebtoon.get(),
            ),
            doubleTapZoom = webtoonDoubleTapZoomEnabled.get(),
            navigation = NavigationSettings(
                navigationModeWebtoon.get(), // RK
                webtoonNavInverted.get(),
                tapBottomZoneHeight.get(), // RK
            ),
            volumeKeysScrollFraction = readWithVolumeKeysScrollAmount.get(), // RK
        )
    }

    /**
     * Settings only the paged WebGPU viewers use.
     */
    val webgpuPaged = settings(
        preferences.dualPageView,
        preferences.transitionAnimation,
        preferences.transitionAnimationDual,
        preferences.cutoutMode,
        preferences.cutoutModeDual,
        preferences.disableDoubleTapGestures,
        preferences.imageFilters,
        preferences.tileFilters,
    ) {
        WebGpuPagedSettings(
            page = WebGpuPagedSettings.Page(
                dualPageView = dualPageView.get(),
                transitionAnimation = transitionAnimation.get(),
                transitionAnimationDual = transitionAnimationDual.get(),
                cutoutMode = cutoutMode.get(),
                cutoutModeDual = cutoutModeDual.get(),
            ),
            common = WebGpuSettings(
                doubleTapGesturesDisabled = disableDoubleTapGestures.get(),
                imageFilters = imageFilters.get(),
                tileFilters = tileFilters.get(),
            ),
        )
    }

    /**
     * Settings only the long strip WebGPU viewer uses.
     */
    val webgpuLongStrip = settings(
        preferences.continuousMinWidth,
        preferences.continuousGap,
        preferences.disableDoubleTapGestures,
        preferences.imageFilters,
        preferences.tileFilters,
    ) {
        WebGpuLongStripSettings(
            page = WebGpuLongStripSettings.Page(
                continuousMinWidth = continuousMinWidth.get(),
                continuousGap = continuousGap.get(),
            ),
            common = WebGpuSettings(
                doubleTapGesturesDisabled = disableDoubleTapGestures.get(),
                imageFilters = imageFilters.get(),
                tileFilters = tileFilters.get(),
            ),
        )
    }

    // RK --> the settings sheet writes through these, and ReaderActivity supplies them
    fun setReadingMode(readingMode: ReadingMode) = onChangeReadingMode(readingMode)

    fun setOrientation(orientation: ReaderOrientation) = onChangeOrientation(orientation)
    // RK <--

    private val viewerMode = MutableStateFlow<ViewerMode?>(null)

    /**
     * Tap zones of the open viewer. Null until a viewer is made.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val navigation: StateFlow<ViewerNavigation?> = viewerMode
        .flatMapLatest { viewerMode ->
            if (viewerMode == null) return@flatMapLatest flowOf(null)

            val usesWebtoonZones = viewerMode.readingMode.type == ReadingMode.ViewerType.Webtoon &&
                !viewerMode.isWebGpu
            val settings = if (usesWebtoonZones) {
                simpleLongStrip.map { it.navigation }
            } else {
                paged.map { it.navigation }
            }
            val isReversed = viewerMode.readingMode == ReadingMode.RIGHT_TO_LEFT
            val defaultNavigation: (ReaderPreferences.TappingInvertMode, Boolean) -> ViewerNavigation =
                if (viewerMode.readingMode.direction == ReadingMode.Direction.Vertical) {
                    ::LNavigation
                } else {
                    ::RightAndLeftNavigation
                }
            settings.distinctUntilChanged().map { it.navigator(isReversed, defaultNavigation) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Called when a viewer is made for [readingMode].
     */
    fun setViewerReadingMode(readingMode: ReadingMode, isWebGpu: Boolean) { // RK: the host says which viewer it built
        viewerMode.value = ViewerMode(readingMode, isWebGpu)
    }

    /**
     * Whether a viewer must show its tap zones because the reader is new to the user, which it is only once.
     */
    fun takeNavigationOverlayForNewUser(): Boolean {
        val show = preferences.showNavigationOverlayNewUser.get()
        if (show) {
            preferences.showNavigationOverlayNewUser.set(false)
        }
        return show
    }

    /**
     * A value [read] from [sources], kept current as any of them changes. Started eagerly, so a viewer reading it
     * before collecting gets what the preferences hold now.
     */
    private fun <T> settings(vararg sources: Preference<*>, read: ReaderPreferences.() -> T): StateFlow<T> {
        return sources.map { it.changes() }
            .merge()
            .map { preferences.read() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.read())
    }

    private data class ViewerMode(
        val readingMode: ReadingMode,
        val isWebGpu: Boolean,
    )
}
